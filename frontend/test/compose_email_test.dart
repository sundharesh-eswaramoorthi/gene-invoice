import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/data_table_scaffold.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/compose_email_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/email.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/email_list_editor.dart';

const _me = CurrentUser(
  id: 7,
  username: 'cara',
  fullName: 'Cara Collins',
  role: 'COLLECTION_POC',
  privileges: {Privileges.emailSend, Privileges.emailView},
  customerId: null,
);

/// Answers the compose form's requests and records every POST it makes.
Dio _dio(List<RequestOptions> posts, {Object? postReply}) => Dio()
  ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    Object? data;
    if (options.method == 'POST') {
      posts.add(options);
      data = postReply ?? <String, dynamic>{};
    } else if (options.path == '/api/emails/addresses') {
      data = {
        'customerId': 3,
        'customerName': 'Acme Ltd',
        'addresses': ['billing@acme.test', 'ap@acme.test'],
      };
    } else if (options.path == '/api/emails/roles') {
      data = [
        {'id': 5, 'name': 'COLLECTION_POC', 'email': 'collections@gene.test', 'memberCount': 2},
      ];
    } else {
      data = <dynamic>[];
    }
    handler.resolve(Response(requestOptions: options, statusCode: 200, data: data));
  }));

Future<void> _pump(WidgetTester tester, Dio dio, Widget Function(BuildContext) opener) async {
  tester.view.physicalSize = const Size(1366, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWithValue(_me),
    ],
    child: MaterialApp(home: Scaffold(body: Builder(builder: opener))),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  test('an address typed but never added is kept on save, and a bad one is caught', () {
    expect(withTypedEmail(['ap@acme.test'], '  ceo@acme.test '), ['ap@acme.test', 'ceo@acme.test']);
    expect(withTypedEmail(['ap@acme.test'], 'AP@acme.test'), ['ap@acme.test']);
    expect(withTypedEmail(['ap@acme.test'], ''), ['ap@acme.test']);
    expect(withTypedEmail(['ap@acme.test'], 'not an address'), isNull);
  });

  test('the form needs something to send about, a sender, a recipient and a subject', () {
    final none = checkCompose(hasTarget: false, hasSender: false, recipients: 0, subject: '   ');
    expect(none.target, isNotNull);
    expect(none.from, isNotNull);
    expect(none.to, 'Add at least one recipient');
    expect(none.subject, 'Enter a subject');

    final ok = checkCompose(hasTarget: true, hasSender: true, recipients: 1, subject: 'Hello');
    expect(ok.isEmpty, isTrue);
  });

  testWidgets('one email about a customer goes to the addresses picked, from the signed-in user',
      (tester) async {
    final posts = <RequestOptions>[];
    bool? sent;
    await _pump(
      tester,
      _dio(posts),
      (context) => TextButton(
        onPressed: () async => sent = await showSendEmailDialog(context,
            type: EmailTargetType.customer, id: 3, label: 'Acme Ltd'),
        child: const Text('open'),
      ),
    );

    expect(find.text('About: Acme Ltd'), findsOneWidget);
    expect(find.textContaining('Cara Collins'), findsOneWidget);

    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Add at least one recipient'), findsOneWidget);
    expect(find.text('Enter a subject'), findsOneWidget);
    expect(posts, isEmpty);

    await tester.tap(find.text('ap@acme.test'));
    await tester.enterText(find.widgetWithText(TextField, 'Subject *'), '  Overdue balance ');
    await tester.pumpAndSettle();
    expect(find.text('Add at least one recipient'), findsNothing);

    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();

    expect(posts, hasLength(1));
    expect(posts.single.path, '/api/emails');
    final body = posts.single.data as Map<String, dynamic>;
    expect(body['customerId'], 3);
    expect(body['from'], {'type': 'USER', 'id': 7});
    expect(body['to']['customerEmails'], ['ap@acme.test']);
    expect(body['to']['userIds'], isEmpty);
    expect(body['subject'], 'Overdue balance');
    expect(body['body'], '');
    expect(sent, isTrue);
    expect(find.text('Email sent'), findsOneWidget);
  });

  testWidgets('a role can be the sender, and shows its mailbox', (tester) async {
    final posts = <RequestOptions>[];
    await _pump(
      tester,
      _dio(posts),
      (context) => TextButton(
        onPressed: () => showSendEmailDialog(context, type: EmailTargetType.invoice, id: 9),
        child: const Text('open'),
      ),
    );

    await tester.tap(find.text('Role'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('COLLECTION_POC — collections@gene.test').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Add role'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('COLLECTION_POC'));
    await tester.pumpAndSettle();
    expect(find.text('COLLECTION_POC · 2 people'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextField, 'Subject *'), 'Reminder');
    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();

    final body = posts.single.data as Map<String, dynamic>;
    expect(body['invoiceId'], 9);
    expect(body['from'], {'type': 'ROLE', 'id': 5});
    expect(body['to']['roleIds'], [5]);
  });

  testWidgets('a bulk send fills the form in once and reports created and skipped rows',
      (tester) async {
    final posts = <RequestOptions>[];
    final reply = {
      'action': 'SEND_EMAIL',
      'requested': 2,
      'succeeded': [3],
      'failed': <dynamic>[],
      'skipped': [
        {'id': 4, 'reason': 'Globex Corp has no email address, and no one else in To would receive it'},
      ],
      'truncated': false,
      'limit': 5000,
    };
    bool? changed;
    await _pump(
      tester,
      _dio(posts, postReply: reply),
      (context) => TextButton(
        onPressed: () async => changed = await sendBulkEmail(
          context,
          EmailTargetType.customer,
          const BulkSelection(
              ids: [3, 4], allMatching: false, count: 2, sort: 'name,asc', filters: []),
        ),
        child: const Text('open'),
      ),
    );

    expect(find.text('Send email to 2 customers'), findsOneWidget);
    await tester.tap(find.text('Every email address of each customer'));
    await tester.enterText(find.widgetWithText(TextField, 'Subject *'), 'Statement');
    await tester.tap(find.text('Send 2 emails'));
    await tester.pumpAndSettle();

    final body = posts.single.data as Map<String, dynamic>;
    expect(posts.single.path, '/api/emails/bulk');
    expect(body['targetType'], 'CUSTOMER');
    expect(body['ids'], [3, 4]);
    expect(body['email']['to']['allCustomerEmails'], isTrue);
    expect((body['email']['to'] as Map).containsKey('customerEmails'), isFalse);

    expect(find.text('1 email created, 1 row skipped'), findsOneWidget);
    expect(find.textContaining('Globex Corp has no email address'), findsOneWidget);
    await tester.tap(find.text('Close'));
    await tester.pumpAndSettle();
    expect(changed, isTrue);
  });
}
