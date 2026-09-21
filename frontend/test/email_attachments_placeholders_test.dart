import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_actions.dart';
import 'package:gene_invoice/features/email/send_email_dialog.dart' show insertAtCursor;
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

const _staff = CurrentUser(
  id: 3,
  username: 'jane',
  fullName: 'Jane Doe',
  role: 'CASHIER',
  privileges: {Privileges.emailView, Privileges.emailSend, Privileges.documentView},
  customerId: null,
);

/// The server's own words when an email carries documents the mail service cannot send
/// (`EmailAttachments.NOT_CARRIED`). The form shows whatever the preview warns, never its own
/// version of it.
const _notCarried = 'Attachments are kept with the email, but the mail service cannot send files '
    'yet, so they will not reach the recipients.';

Map<String, dynamic> _context() => {
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'delivery': {'configured': true},
      'sender': {
        'restricted': false,
        'self': {'userId': 3, 'name': 'Jane Doe', 'email': 'jane@company.com', 'gmail': 'CONNECTED'},
      },
      'roles': <Map<String, dynamic>>[],
      'customerEmails': {
        'available': true,
        'addresses': [
          {'name': 'Acme Ltd', 'address': 'ap@acme.com'},
        ],
      },
    };

Map<String, dynamic> _preview({
  List<String> warnings = const [],
  String? subject,
  String? body,
}) =>
    {
      'from': {'name': 'Jane Doe', 'address': 'jane@company.com', 'internal': true, 'masked': false},
      'to': [
        {
          'name': 'Acme Ltd',
          'address': 'ap@acme.com',
          'internal': false,
          'masked': false,
          'sources': [
            {'type': 'CUSTOMER'},
          ],
        },
      ],
      'unresolved': <Map<String, dynamic>>[],
      'problems': <String>[],
      'warnings': warnings,
      if (subject != null) 'subject': subject,
      if (body != null) 'body': body,
    };

FakeBackend _backend({Map<String, Object? Function(dynamic)>? extra}) => FakeBackend({
      'GET /api/emails/context': (_) => _context(),
      'POST /api/emails/preview': (_) => _preview(),
      'GET /api/emails/attachable': (_) => [
            {
              'id': 7,
              'filename': 'invoice.pdf',
              'contentType': 'application/pdf',
              'sizeBytes': 2048,
              'source': 'RECORD',
              'sourceLabel': 'This invoice',
            },
            {
              'id': 9,
              'filename': 'terms.png',
              'contentType': 'image/png',
              'sizeBytes': 512,
              'source': 'CUSTOMER',
              'sourceLabel': 'Customer',
            },
          ],
      'GET /api/emails/placeholders': (_) => [
            {
              'label': 'Customer',
              'placeholders': [
                {'key': '{{Customer.Name}}', 'label': 'Customer name', 'sample': 'Acme Ltd'},
                {'key': '{{Customer.Phone}}', 'label': 'Customer phone', 'sample': ''},
              ],
            },
            {
              'label': 'Invoice',
              'placeholders': [
                {'key': '{{Invoice.Balance}}', 'label': 'Balance', 'sample': '₹1,200.00'},
              ],
            },
          ],
      'POST /api/emails': (_) => {
            'id': 91,
            'entityType': 'INVOICE',
            'entityId': 42,
            'entityLabel': 'Invoice INV-0042',
            'direction': 'OUTBOUND',
            'status': 'SENT',
            'subject': 'Payment reminder',
            'body': '',
            'from': {'name': 'Jane Doe', 'address': 'jane@company.com', 'internal': true, 'masked': false},
            'to': <Map<String, dynamic>>[],
            'cc': <Map<String, dynamic>>[],
            'unresolved': <Map<String, dynamic>>[],
            'attachments': [
              {
                'id': 1,
                'documentId': 7,
                'filename': 'invoice.pdf',
                'contentType': 'application/pdf',
                'sizeBytes': 2048,
              },
            ],
            'attempts': 1,
            'occurredAt': '2026-09-17T10:15:00Z',
          },
      ...?extra,
    });

Future<void> _open(WidgetTester tester, FakeBackend backend,
    {Size size = const Size(1366, 1100)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(_staff),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Builder(
          builder: (context) => Center(
            child: TextButton(
              onPressed: () => showSendEmailDialog(context,
                  type: EmailEntityType.invoice, entityId: 42, entityLabel: 'Invoice INV-0042'),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

/// Lets the debounced preview go out and come back.
Future<void> _settlePreview(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 500));
  await tester.pumpAndSettle();
}

void main() {
  group('inserting at the cursor', () {
    test('goes where the cursor is, replacing what is selected', () {
      final controller = TextEditingController(text: 'Dear , your invoice');
      controller.selection = const TextSelection.collapsed(offset: 5);
      insertAtCursor(controller, '{{Customer.Name}}');
      expect(controller.text, 'Dear {{Customer.Name}}, your invoice');
      // The cursor is left after what was put in, ready to keep typing.
      expect(controller.selection.baseOffset, 5 + '{{Customer.Name}}'.length);

      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 4);
      insertAtCursor(controller, 'Hi');
      expect(controller.text, 'Hi {{Customer.Name}}, your invoice');
    });

    test('a field never focused has no cursor, so it goes on the end', () {
      final controller = TextEditingController(text: 'Reminder');
      expect(controller.selection.isValid, isFalse);
      insertAtCursor(controller, ' {{Invoice.Balance}}');
      expect(controller.text, 'Reminder {{Invoice.Balance}}');
    });
  });

  group('attachments', () {
    testWidgets('the picker groups the record\'s documents and its customer\'s, with type and size',
        (tester) async {
      final backend = _backend();
      await _open(tester, backend);

      // Nothing is asked for until the picker is opened: a compose form is opened far more often
      // than a file is attached.
      expect(backend.sent('GET /api/emails/attachable'), isEmpty);
      await tester.tap(find.widgetWithText(ActionChip, 'Attach…'));
      await tester.pumpAndSettle();

      final asked = backend.sent('GET /api/emails/attachable').single;
      expect(asked.queryParameters, {'entityType': 'INVOICE', 'entityId': 42});
      expect(find.text('This invoice'), findsOneWidget);
      expect(find.text('Customer'), findsOneWidget);
      expect(find.text('invoice.pdf'), findsOneWidget);
      expect(find.text('PDF · 2 KB'), findsOneWidget);
      expect(find.text('terms.png'), findsOneWidget);
      expect(find.text('PNG · 512 B'), findsOneWidget);
    });

    testWidgets('a chosen document becomes a chip and goes out as a document id', (tester) async {
      final backend = _backend();
      await _open(tester, backend);

      await tester.tap(find.widgetWithText(ActionChip, 'Attach…'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('invoice.pdf'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Attach 1'));
      await tester.pumpAndSettle();

      expect(find.widgetWithText(InputChip, 'invoice.pdf · 2 KB'), findsOneWidget);

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Subject *'), 'Payment reminder');
      await _settlePreview(tester);
      // The preview describes the form as it stands, attachments included, which is how the
      // server gets to warn about them.
      expect(backend.sent('POST /api/emails/preview').last.data['documentIds'], [7]);

      await tester.tap(find.text('Send'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/emails').single.data['documentIds'], [7]);
    });

    testWidgets('an attachment the mail service cannot send says so in the server\'s own words',
        (tester) async {
      final backend = _backend(extra: {
        'POST /api/emails/preview': (_) => _preview(warnings: [_notCarried]),
      });
      await _open(tester, backend);

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Subject *'), 'Payment reminder');
      await _settlePreview(tester);

      expect(find.text(_notCarried), findsOneWidget);
      // It is a warning, not a problem: the email is still worth saving.
      final send = tester.widget<FilledButton>(find.ancestor(
          of: find.text('Send'), matching: find.byType(FilledButton)));
      expect(send.onPressed, isNotNull);
    });
  });

  group('placeholders', () {
    testWidgets('the list shows what each field says on this record, and inserts the key',
        (tester) async {
      final backend = _backend();
      await _open(tester, backend);

      expect(backend.sent('GET /api/emails/placeholders'), isEmpty);
      await tester.tap(find.widgetWithText(ActionChip, 'Insert field').first);
      await tester.pumpAndSettle();

      expect(backend.sent('GET /api/emails/placeholders').single.queryParameters,
          {'entityType': 'INVOICE', 'entityId': 42});
      expect(find.text('Customer name'), findsOneWidget);
      expect(find.text('{{Customer.Name}}'), findsOneWidget);
      expect(find.text('Acme Ltd'), findsOneWidget);
      expect(find.text('₹1,200.00'), findsOneWidget);
      // An empty sample is the record having nothing there, said rather than shown as a blank.
      expect(find.text('Nothing on this record'), findsOneWidget);

      await tester.tap(find.text('Customer name'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextFormField, '{{Customer.Name}}'), findsOneWidget);
    });

    testWidgets('the preview shows the text as it will read once the fields are filled in',
        (tester) async {
      final backend = _backend(extra: {
        'POST /api/emails/preview': (_) =>
            _preview(subject: 'Acme Ltd — payment due', body: 'You owe ₹1,200.00'),
      });
      await _open(tester, backend);

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'),
          '{{Customer.Name}} — payment due');
      await _settlePreview(tester);

      expect(find.text('As it will read'), findsOneWidget);
      expect(find.text('Acme Ltd — payment due'), findsOneWidget);
      expect(find.text('You owe ₹1,200.00'), findsOneWidget);
    });

    testWidgets('nothing to fill in means nothing to show a second time', (tester) async {
      final backend = _backend(extra: {
        'POST /api/emails/preview': (_) => _preview(subject: 'Payment reminder', body: ''),
      });
      await _open(tester, backend);

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Subject *'), 'Payment reminder');
      await _settlePreview(tester);

      // The typed subject is already on the form a few lines above; repeating it says nothing.
      expect(find.text('As it will read'), findsNothing);
      expect(find.text('Payment reminder'), findsOneWidget);
    });
  });
}
