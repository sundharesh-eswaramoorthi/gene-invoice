import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_view.dart';
import 'package:gene_invoice/features/email/inbox_screen.dart';
import 'package:gene_invoice/shared/models/email.dart';

Map<String, dynamic> _email(int id, String subject, {String? invoiceNumber, String body = ''}) => {
      'id': id,
      'targetType': invoiceNumber == null ? 'CUSTOMER' : 'INVOICE',
      'customerId': 3,
      'customerName': 'Acme Ltd',
      'invoiceId': invoiceNumber == null ? null : 11,
      'invoiceNumber': invoiceNumber,
      'from': {'type': 'ROLE', 'roleId': 5, 'name': 'COLLECTION_POC', 'address': null},
      'to': [
        {'type': 'USER', 'userId': 7, 'name': 'Cara Collins', 'address': 'cara@gene.test', 'members': []},
        {
          'type': 'ROLE',
          'roleId': 5,
          'name': 'COLLECTION_POC',
          'address': 'collections@gene.test',
          'members': [
            {'userId': 7, 'name': 'Cara Collins', 'address': 'cara@gene.test'},
            {'userId': 8, 'name': 'Cole Carter', 'address': null},
          ],
        },
        {'type': 'CUSTOMER_EMAIL', 'address': 'billing@acme.test', 'members': []},
      ],
      'subject': subject,
      'body': body,
      'sentByUserId': 1,
      'sentByName': 'System Administrator',
      'sentAt': '2026-09-17T04:30:00Z',
    };

void main() {
  testWidgets('an email shows its sender, every kind of recipient, who sent it and when',
      (tester) async {
    final email = EmailMessage.fromJson(_email(1, 'Overdue', invoiceNumber: 'INV-0042', body: 'Please pay.'));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: EmailDetails(email: email, about: EmailAbout.invoicesOnly)),
    ));

    expect(find.text('Overdue'), findsOneWidget);
    // A role with no mailbox says so rather than leaving a blank.
    expect(find.text('COLLECTION_POC (role) — no email address'), findsOneWidget);
    expect(find.text('Cara Collins <cara@gene.test>'), findsOneWidget);
    expect(find.textContaining('Cara Collins, Cole Carter'), findsOneWidget);
    expect(find.text('Customer: billing@acme.test'), findsOneWidget);
    expect(find.text('System Administrator'), findsOneWidget);
    expect(find.text('Please pay.'), findsOneWidget);
    // On a customer's tab an invoice's email is labelled with the invoice number.
    expect(find.text('Invoice INV-0042'), findsOneWidget);
  });

  testWidgets('on a customer\'s tab its own emails carry no label, and an empty body says so',
      (tester) async {
    final email = EmailMessage.fromJson(_email(1, 'Hello'));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: EmailDetails(email: email, about: EmailAbout.invoicesOnly)),
    ));
    expect(find.byType(EmailAboutChip), findsNothing);
    expect(find.text('No message'), findsOneWidget);
  });

  testWidgets('the Inbox shows unread emails differently and marks all as read in one call',
      (tester) async {
    tester.view.physicalSize = const Size(1366, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final posts = <String>[];
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        Object data;
        if (options.method == 'POST') {
          posts.add(options.path);
          data = {'updated': 1};
        } else if (options.path.startsWith('/api/table-schemas/')) {
          data = {'entity': 'inbox', 'columns': <dynamic>[]};
        } else {
          data = {
            'content': [
              {'id': 2, 'read': false, 'readAt': null, 'email': _email(2, 'Unread one')},
              {
                'id': 1,
                'read': true,
                'readAt': '2026-09-17T05:00:00Z',
                'email': _email(1, 'Read one', invoiceNumber: 'INV-0042'),
              },
            ],
            'page': 0,
            'size': 20,
            'totalElements': 2,
            'totalPages': 1,
          };
        }
        handler.resolve(Response(requestOptions: options, statusCode: 200, data: data));
      }));

    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(null),
      ],
      child: const MaterialApp(home: InboxScreen(query: TableQuery(sort: 'sentAt,desc'))),
    ));
    await tester.pumpAndSettle();

    // No search or filters in the Inbox.
    expect(find.text('Add filter'), findsNothing);

    FontWeight? subjectWeight(String subject) {
      final span = tester.widget<Text>(find.text(subject)).textSpan! as TextSpan;
      return span.children!.first.style?.fontWeight;
    }

    expect(subjectWeight('Unread one'), FontWeight.w700);
    expect(subjectWeight('Read one'), isNull);
    expect(find.bySemanticsLabel('Unread'), findsOneWidget);
    expect(find.bySemanticsLabel('Read'), findsOneWidget);
    expect(find.text('INV-0042 • Acme Ltd'), findsOneWidget);

    // Only the unread one offers "Mark as read".
    expect(find.byTooltip('Mark as read'), findsOneWidget);

    await tester.tap(find.text('Mark all as read'));
    await tester.pumpAndSettle();
    expect(posts, ['/api/inbox/read-all']);
  });
}
