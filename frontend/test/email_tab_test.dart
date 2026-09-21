import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_actions.dart';
import 'package:gene_invoice/features/email/email_models.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/email/email_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/detail_scaffold.dart';
import 'package:go_router/go_router.dart';

CurrentUser _user(Set<String> privileges, {int? customerId}) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
    );

/// What staff see: every name and address, and how each person came to be on the email.
Map<String, dynamic> _staffEmail(int id) => {
      'id': id,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'entityLink': '/invoices/42',
      'direction': 'OUTBOUND',
      'status': 'FAILED',
      'subject': 'Payment reminder',
      'body': 'Hello,\nplease pay INV-0042.',
      'from': {
        'name': 'Jane Doe',
        'address': 'jane@company.com',
        'userId': 3,
        'internal': true,
        'masked': false,
        'sources': [],
      },
      'fromRole': 'COLLECTION_POC',
      'fromRoleLabel': 'Collection POC',
      'to': [
        {
          'name': 'Bob Smith',
          'address': 'bob@company.com',
          'userId': 12,
          'internal': true,
          'masked': false,
          'sources': [
            {'type': 'USER'},
            {'type': 'ROLE', 'role': 'SALES_POC', 'label': 'Sales POC'},
          ],
        },
        {
          'name': 'Acme Ltd',
          'address': 'ap@acme.com',
          'internal': false,
          'masked': false,
          'sources': [
            {'type': 'CUSTOMER'},
          ],
        },
        {
          'name': 'Pat Nomail',
          'address': null,
          'userId': 14,
          'internal': true,
          'masked': false,
          'sources': [
            {'type': 'USER'},
          ],
        },
      ],
      'cc': [
        {
          'name': 'ops@acme.com',
          'address': 'ops@acme.com',
          'internal': false,
          'masked': false,
          'sources': [
            {'type': 'HEADER'},
          ],
        },
      ],
      'unresolved': [
        {
          'token': 'ROLE:CUSTOMER_SUCCESS_POC',
          'label': 'Customer Success POC',
          'reason': 'Nobody holds Customer Success POC on Invoice INV-0042',
        },
      ],
      'sentBy': {'userId': 3, 'name': 'Jane Doe'},
      'deliveredFrom': 'billing@company.com',
      'error': 'Mailbox refused the message',
      'attempts': 3,
      'occurredAt': '2026-09-17T10:15:00Z',
      'sentAt': null,
      'canRetry': true,
      'readByMe': null,
    };

/// The same kind of email as a customer login sees it: staff reduced to a role or the team (E13).
Map<String, dynamic> _maskedEmail(int id) => {
      'id': id,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'entityLink': '/invoices/42',
      'direction': 'OUTBOUND',
      'status': 'SENT',
      'subject': 'Your invoice',
      'body': '',
      'from': {
        'name': 'Collection POC',
        'address': null,
        'userId': null,
        'internal': true,
        'masked': true,
        'sources': [],
      },
      'fromRole': 'COLLECTION_POC',
      'fromRoleLabel': 'Collection POC',
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
        {
          'name': 'Sales POC',
          'address': null,
          'userId': null,
          'internal': true,
          'masked': true,
          'sources': [
            {'type': 'ROLE', 'role': 'SALES_POC', 'label': 'Sales POC'},
          ],
        },
      ],
      'cc': [],
      'unresolved': [],
      'sentBy': {'userId': null, 'name': 'Gene Invoice team'},
      'deliveredFrom': 'billing@company.com',
      'error': null,
      'attempts': 1,
      'occurredAt': '2026-09-17T09:00:00Z',
      'sentAt': '2026-09-17T09:00:01Z',
      'canRetry': false,
      'readByMe': true,
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> content, {int page = 0, int? total}) {
  final count = total ?? content.length;
  return {
    'content': content,
    'page': page,
    'size': 20,
    'totalElements': count,
    'totalPages': (count / 20).ceil(),
  };
}

Future<List<RequestOptions>> _pump(
  WidgetTester tester, {
  required CurrentUser user,
  required Map<String, dynamic> Function(int page) pages,
  Size size = const Size(1366, 1400),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final requests = <RequestOptions>[];
  final dio = Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      requests.add(options);
      handler.resolve(Response(
        requestOptions: options,
        statusCode: 200,
        data: pages(options.queryParameters['page'] as int? ?? 0),
      ));
    }));

  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Consumer(builder: (context, ref, _) {
          final tab = emailDetailTab(ref,
              type: EmailEntityType.invoice, entityId: 42, entityLabel: 'Invoice INV-0042');
          return DetailScaffold(
            title: 'INV-0042',
            top: const SizedBox(height: 40),
            initialTabSlug: 'email',
            tabs: [
              DetailTab(
                  slug: 'history',
                  label: 'History',
                  icon: Icons.history,
                  builder: (_) => const SizedBox()),
              if (tab != null) tab,
            ],
          );
        }),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return requests;
}

void main() {
  testWidgets('staff see every detail of an email, and may retry a failed one', (tester) async {
    final requests = await _pump(
      tester,
      user: _user({Privileges.emailView, Privileges.emailSend}),
      pages: (_) => _page([_staffEmail(1)]),
    );

    expect(requests.single.path, '/api/emails');
    expect(requests.single.queryParameters,
        {'entityType': 'INVOICE', 'entityId': 42, 'page': 0, 'size': 20, 'sort': 'occurredAt,desc'});

    expect(find.text('Emails (1)'), findsOneWidget);
    expect(find.text('Payment reminder'), findsOneWidget);
    expect(find.text('Failed'), findsOneWidget);
    expect(find.text('Outgoing · written ${formatDateTime('2026-09-17T10:15:00Z')}, failed'),
        findsOneWidget);
    expect(_person('Jane Doe <jane@company.com> · as Collection POC'), findsOneWidget);
    expect(_person('Bob Smith <bob@company.com> — added directly, Sales POC'), findsOneWidget);
    expect(_person('Acme Ltd <ap@acme.com> — customer email'), findsOneWidget);
    expect(_person('Pat Nomail — added directly — no email address'), findsOneWidget);
    expect(_person('ops@acme.com — as addressed'), findsOneWidget);
    expect(find.text('Customer Success POC — nobody assigned'), findsOneWidget);
    expect(find.text('Jane Doe'), findsNWidgets(2)); // the sender, and who sent it
    expect(find.text('billing@company.com'), findsOneWidget);
    expect(find.text('Mailbox refused the message (after 3 attempts)'), findsOneWidget);
    expect(find.text('Hello,\nplease pay INV-0042.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    expect(find.text('Send email'), findsOneWidget);
  });

  testWidgets('a customer login reads staff as their role or the team, with no addresses',
      (tester) async {
    await _pump(
      tester,
      user: _user({Privileges.emailView}, customerId: 5),
      pages: (_) => _page([_maskedEmail(2)]),
      size: const Size(400, 1400),
    );

    expect(tester.takeException(), isNull);
    // The masked sender already reads as the role: no "Collection POC · as Collection POC".
    expect(find.text('Collection POC'), findsOneWidget);
    expect(find.textContaining('as Collection POC'), findsNothing);
    // A masked recipient is named by the role alone, not "Sales POC — Sales POC".
    expect(find.text('Sales POC'), findsOneWidget);
    expect(find.text('Gene Invoice team'), findsOneWidget);
    expect(find.textContaining('no email address'), findsNothing);
    expect(find.textContaining('null'), findsNothing);
    expect(find.text('Sent'), findsOneWidget);
    expect(find.text('(no message)'), findsOneWidget);
    // No EMAIL_SEND: nothing to send or retry with.
    expect(find.text('Send email'), findsNothing);
    expect(find.text('Retry'), findsNothing);
  });

  testWidgets('older emails load a page at a time, below the newer ones', (tester) async {
    final requests = await _pump(
      tester,
      user: _user({Privileges.emailView}),
      pages: (page) => page == 0
          ? _page([for (var i = 1; i <= 20; i++) _maskedEmail(i)], total: 21)
          : _page([_staffEmail(21)], page: 1, total: 21),
    );

    await tester.scrollUntilVisible(find.text('Show older emails (1 more)'), 400,
        scrollable: _list);
    await tester.tap(find.text('Show older emails (1 more)'));
    await tester.pumpAndSettle();

    expect(requests.map((r) => r.queryParameters['page']), [0, 1]);
    expect(find.textContaining('Show older emails'), findsNothing);
    await tester.scrollUntilVisible(find.text('Payment reminder'), 400,
        scrollable: _list);
    expect(find.text('Payment reminder'), findsOneWidget);
  });

  testWidgets('an empty record says so', (tester) async {
    await _pump(tester, user: _user({Privileges.emailView}), pages: (_) => _page([]));
    expect(find.text('No emails about this invoice yet.'), findsOneWidget);
  });

  // An email sent to carry a file is the one email where the record of what went out is the
  // point of it (E17). The name, kind and size are the email's own snapshot, so they read the
  // same after the document itself has been deleted.
  group('what went out with an email', () {
    Map<String, dynamic> withFiles(Map<String, dynamic> email, {bool canOpenRecord = true}) => {
          ...email,
          'canOpenRecord': canOpenRecord,
          'attachments': [
            {
              'id': 1,
              'documentId': 9,
              'filename': 'statement.pdf',
              'contentType': 'application/pdf',
              'sizeBytes': 1536,
            },
            // Attached from a document since deleted: there is nowhere to send a reader, and the
            // email still says what it carried.
            {
              'id': 2,
              'documentId': null,
              'filename': 'photo.png',
              'contentType': 'image/png',
              'sizeBytes': 2048,
            },
          ],
        };

    testWidgets('each file is named, with its kind and size', (tester) async {
      await _pump(
        tester,
        user: _user({Privileges.emailView}),
        pages: (_) => _page([withFiles(_staffEmail(1))]),
      );

      expect(find.text('Attached'), findsOneWidget);
      expect(find.text('statement.pdf'), findsOneWidget);
      // The Documents tab's own words for a kind and a size, so a file reads the same in both.
      expect(find.text('· PDF · 1.5 KB'), findsOneWidget);
      expect(find.text('photo.png'), findsOneWidget);
      expect(find.text('· PNG · 2 KB'), findsOneWidget);
      // Only the one whose document is still there is a way back to it.
      expect(find.byTooltip('Open the Documents tab'), findsOneWidget);
    });

    testWidgets('an email that carried nothing says nothing about it', (tester) async {
      await _pump(
        tester,
        user: _user({Privileges.emailView}),
        pages: (_) => _page([_staffEmail(1)]),
      );
      expect(find.text('Attached'), findsNothing);
    });

    testWidgets('a record this reader may not open is no link at all', (tester) async {
      await _pump(
        tester,
        user: _user({Privileges.emailView}),
        // A recipient may read an email about a record they cannot see; a link would only lead
        // to a refusal.
        pages: (_) => _page([withFiles(_staffEmail(1), canOpenRecord: false)]),
      );

      expect(find.text('statement.pdf'), findsOneWidget);
      expect(find.byTooltip('Open the Documents tab'), findsNothing);
    });

    testWidgets('the filename leads to the Documents tab, where the download lives',
        (tester) async {
      tester.view.physicalSize = const Size(1366, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      final router = GoRouter(
        initialLocation: '/emails',
        routes: [
          GoRoute(
            path: '/emails',
            builder: (_, __) => Scaffold(
              body: SingleChildScrollView(
                child: EmailCard(email: EmailMessage.fromJson(withFiles(_staffEmail(1)))),
              ),
            ),
          ),
          GoRoute(
            path: '/invoices/:id',
            builder: (_, s) => Text(
                'invoice ${s.pathParameters['id']} tab=${s.uri.queryParameters['tab']}'),
          ),
        ],
      );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(Dio()),
          currentUserProvider.overrideWithValue(_user({Privileges.emailView})),
        ],
        child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('statement.pdf'));
      await tester.pumpAndSettle();
      // The document's own visibility is applied by the tab that downloads it (D7); nothing on
      // the email decides who may have the bytes.
      expect(find.text('invoice 42 tab=documents'), findsOneWidget);
    });
  });

  group('an email a customer login wrote is never offered a retry (QMX-4)', () {
    /// Saved, and never handed to Gmail: a customer login has no connection to send through
    /// (mail-service.md M13), so a retry would come back with the same words every time.
    Map<String, dynamic> notSent(Map<String, dynamic> email) => {
          ...email,
          'status': 'NOT_SENT',
          'error': notSentFromCustomerLogin,
          'attempts': 1,
          'canRetry': true,
        };

    testWidgets('the customer who wrote it sees why, and no button', (tester) async {
      await _pump(
        tester,
        user: _user({Privileges.emailView, Privileges.emailSend}, customerId: 5),
        pages: (_) => _page([notSent(_maskedEmail(2))]),
        size: const Size(400, 1400),
      );

      expect(find.text('Not sent'), findsOneWidget);
      expect(find.text(notSentFromCustomerLogin), findsOneWidget);
      expect(find.text('Retry'), findsNothing);
    });

    testWidgets('nor does staff reading the same email', (tester) async {
      await _pump(
        tester,
        user: _user({Privileges.emailView, Privileges.emailSend}),
        pages: (_) => _page([notSent(_staffEmail(1))]),
      );

      expect(find.text(notSentFromCustomerLogin), findsOneWidget);
      expect(find.text('Retry'), findsNothing);
    });
  });

  testWidgets('a card gone before its retry answers still says how it went, and refreshes',
      (tester) async {
    tester.view.physicalSize = const Size(1366, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final answer = Completer<void>();
    final requests = <RequestOptions>[];
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
        requests.add(options);
        final retry = options.path == '/api/emails/1/retry';
        if (retry) await answer.future;
        handler.resolve(Response(
          requestOptions: options,
          statusCode: 200,
          data: retry
              ? {..._staffEmail(1), 'status': 'SENT', 'error': null, 'canRetry': false}
              : _staffEmail(1),
        ));
      }));
    // The Inbox reader closing, or another tab chosen, takes the card away mid-request.
    final showCard = ValueNotifier(true);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        dioProvider.overrideWithValue(dio),
        currentUserProvider.overrideWithValue(_user({Privileges.emailView, Privileges.emailSend})),
      ],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: ValueListenableBuilder<bool>(
            valueListenable: showCard,
            builder: (context, show, _) => show
                ? SingleChildScrollView(
                    child: EmailCard(email: EmailMessage.fromJson(_staffEmail(1)), showRecord: true))
                : const Text('closed'),
          ),
        ),
      ),
    ));
    // Anything else showing this email, such as the Inbox reader, reloads it after a retry.
    ProviderScope.containerOf(tester.element(find.byType(Scaffold)))
        .listen(emailDetailProvider(1), (_, __) {});
    await tester.pumpAndSettle();
    int loads() => requests.where((r) => r.method == 'GET' && r.path == '/api/emails/1').length;
    expect(loads(), 1);

    await tester.tap(find.text('Retry'));
    await tester.pump();
    showCard.value = false;
    await tester.pumpAndSettle();
    expect(find.text('closed'), findsOneWidget);

    answer.complete();
    await tester.pumpAndSettle();
    await tester.pump(Duration.zero);
    expect(find.text('Email sent'), findsOneWidget);
    expect(find.textContaining('Bad state'), findsNothing);
    expect(loads(), 2);
  });

  testWidgets('the time line says what became of an email, rather than "not sent yet" for all',
      (tester) async {
    tester.view.physicalSize = const Size(1366, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final written = formatDateTime('2026-09-17T10:15:00Z');
    final sent = formatDateTime('2026-09-17T10:16:00Z');
    final expected = {
      'QUEUED': 'Outgoing · written $written, waiting to send',
      'SENDING': 'Outgoing · written $written, sending',
      'FAILED': 'Outgoing · written $written, failed',
      'NOT_SENT': 'Outgoing · written $written, not delivered',
      'SENT': 'Outgoing · sent $sent',
      'RECEIVED': 'Incoming · received $written',
    };
    final emails = [
      for (final (i, status) in expected.keys.indexed)
        EmailMessage.fromJson({
          ..._staffEmail(i + 1),
          'status': status,
          'direction': status == 'RECEIVED' ? 'INBOUND' : 'OUTBOUND',
          'sentAt': status == 'SENT' ? '2026-09-17T10:16:00Z' : null,
        }),
    ];
    await tester.pumpWidget(ProviderScope(
      overrides: [currentUserProvider.overrideWithValue(_user({Privileges.emailView}))],
      child: MaterialApp(
        theme: AppTheme.light(),
        home: Scaffold(
          body: ListView(children: [for (final e in emails) EmailCard(email: e)]),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    for (final line in expected.values) {
      await tester.scrollUntilVisible(find.text(line), 300, scrollable: _list);
      expect(find.text(line), findsOneWidget);
    }
    expect(find.textContaining('not sent yet'), findsNothing);
  });

  testWidgets('the Email tab speaks for the system, the Inbox reader for the one who received it',
      (tester) async {
    tester.view.physicalSize = const Size(1366, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final email = EmailMessage.fromJson({..._maskedEmail(5), 'sentAt': '2026-09-17T09:00:01Z'});
    Future<void> pump({required bool inbox}) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [currentUserProvider.overrideWithValue(_user({Privileges.emailView}))],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: Scaffold(body: SingleChildScrollView(child: EmailCard(email: email, inbox: inbox))),
        ),
      ));
      await tester.pumpAndSettle();
    }

    await pump(inbox: false);
    expect(find.text('Outgoing · sent ${formatDateTime('2026-09-17T09:00:01Z')}'), findsOneWidget);
    expect(find.byIcon(Icons.call_made), findsOneWidget);

    // The same email, opened by one of its recipients.
    await pump(inbox: true);
    expect(find.text('Received ${formatDateTime('2026-09-17T09:00:00Z')}'), findsOneWidget);
    expect(find.byIcon(Icons.call_received), findsOneWidget);
    expect(find.byTooltip('Received'), findsOneWidget);
    expect(find.textContaining('Outgoing'), findsNothing);
    expect(find.byIcon(Icons.call_made), findsNothing);
  });

  testWidgets('without EMAIL_VIEW there is no Email tab', (tester) async {
    await _pump(tester, user: _user({Privileges.emailSend}), pages: (_) => _page([]));
    expect(find.text('Email'), findsNothing);
    expect(find.text('History'), findsOneWidget);
  });

  group('delivery to each recipient (mail-service.md §6)', () {
    const sent = '2026-09-17T10:16:00Z';
    const delivered = '2026-09-17T10:31:00Z';
    const read = '2026-09-17T11:00:00Z';
    const readInApp = '2026-09-17T12:00:00Z';
    const t = formatDateTime;

    Map<String, dynamic> customer(String name, Map<String, dynamic>? delivery) => {
          'name': name,
          'address': '${name.toLowerCase()}@acme.com',
          'internal': false,
          'masked': false,
          'sources': [
            {'type': 'CUSTOMER'},
          ],
          if (delivery != null) 'delivery': delivery,
        };

    testWidgets('each To recipient says what became of their own copy', (tester) async {
      tester.view.physicalSize = const Size(1366, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      final email = EmailMessage.fromJson({
        ..._staffEmail(1),
        'status': 'PARTIAL',
        'error': '3 of 10 not delivered: 550 5.1.1 The email account does not exist',
        'attempts': 1,
        'sentAt': sent,
        'to': [
          customer('Ann', {'status': 'QUEUED'}),
          customer('Bob', {'status': 'SENDING'}),
          customer('Cat', {'status': 'SENT', 'sentAt': sent}),
          customer('Dan', {
            'status': 'DELIVERED',
            'sentAt': sent,
            'deliveredAt': delivered,
            'deliveredConfirmed': true,
          }),
          customer('Eve', {'status': 'DELIVERED', 'sentAt': sent, 'deliveredAt': delivered}),
          const {
            'name': 'Fay Staff',
            'address': 'fay@gmail.com',
            'userId': 15,
            'internal': true,
            'masked': false,
            'sources': [
              {'type': 'USER'},
            ],
            'delivery': {
              'status': 'READ',
              'sentAt': sent,
              'deliveredAt': delivered,
              'deliveredConfirmed': true,
              'readAt': read,
              'readInAppAt': readInApp,
            },
          },
          customer('Gus', {
            'status': 'BOUNCED',
            'error': '550 5.1.1 The email account does not exist',
            'sentAt': sent,
            'bouncedAt': delivered,
          }),
          customer('Hal', {'status': 'FAILED', 'error': 'Gmail refused the request (400): Bad To'}),
          customer('Ivy', {'status': 'NOT_SENT', 'error': 'Jane Doe has not connected Gmail'}),
          // In the app only: no address, so no copy — but read in the Inbox.
          const {
            'name': 'Jon Nomail',
            'address': null,
            'userId': 16,
            'internal': true,
            'masked': false,
            'sources': [
              {'type': 'USER'},
            ],
            'delivery': {'status': null, 'readInAppAt': readInApp},
          },
          customer('Kim', null),
        ],
      });
      await tester.pumpWidget(ProviderScope(
        overrides: [currentUserProvider.overrideWithValue(_user({Privileges.emailView}))],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: Scaffold(body: SingleChildScrollView(child: EmailCard(email: email))),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.text('Partly sent'), findsOneWidget);
      expect(find.text('Outgoing · written ${t('2026-09-17T10:15:00Z')}, partly delivered'),
          findsOneWidget);
      for (final line in [
        'Ann <ann@acme.com> — customer email · Queued',
        'Bob <bob@acme.com> — customer email · Sending',
        'Cat <cat@acme.com> — customer email · Sent ${t(sent)}',
        'Dan <dan@acme.com> — customer email · Delivered ${t(delivered)}',
        'Eve <eve@acme.com> — customer email · Delivered ${t(delivered)} (estimated)',
        'Fay Staff <fay@gmail.com> — added directly · Read ${t(read)} '
            '· read in app ${t(readInApp)}',
        'Gus <gus@acme.com> — customer email '
            '· Bounced: 550 5.1.1 The email account does not exist',
        'Hal <hal@acme.com> — customer email · Failed: Gmail refused the request (400): Bad To',
        'Ivy <ivy@acme.com> — customer email · Not sent: Jane Doe has not connected Gmail',
        'Jon Nomail — added directly — no email address · read in app ${t(readInApp)}',
        'Kim <kim@acme.com> — customer email',
      ]) {
        expect(_person(line), findsOneWidget, reason: line);
      }

      // Only an estimate says how little it knows.
      expect(find.byTooltip('No bounce came back; Gmail does not confirm delivery'),
          findsOneWidget);
      expect(
          find.ancestor(
              of: find.text('· Delivered ${t(delivered)} (estimated)'),
              matching: find.byType(Tooltip)),
          findsOneWidget);

      final scheme = Theme.of(tester.element(find.byType(EmailCard))).colorScheme;
      Color? color(String run) => tester.widget<Text>(find.text(run)).style?.color;
      expect(color('· Delivered ${t(delivered)}'), Colors.green.shade700);
      expect(color('· Read ${t(read)}'), Colors.green.shade700);
      expect(color('· Bounced: 550 5.1.1 The email account does not exist'), scheme.error);
      expect(color('· Failed: Gmail refused the request (400): Bad To'), scheme.error);
      expect(color('· Not sent: Jane Doe has not connected Gmail'), scheme.error);
      for (final run in ['· Queued', '· Sending', '· Sent ${t(sent)}']) {
        expect(color(run), isNot(anyOf(Colors.green.shade700, scheme.error)), reason: run);
      }
    });

    test('a copy reads by its status, with its time or its reason', () {
      String? text(Map<String, dynamic> json) =>
          recipientDeliveryText(RecipientDelivery.fromJson(json));
      // Keys the server leaves out read as nothing known.
      expect(text({}), isNull);
      expect(RecipientDelivery.fromJson(const {}).deliveredConfirmed, isFalse);
      expect(text({'status': 'SENT'}), 'Sent');
      expect(text({'status': 'DELIVERED'}), 'Delivered (estimated)');
      expect(text({'status': 'READ', 'readAt': read}), 'Read ${t(read)}');
      expect(text({'status': 'BOUNCED'}), 'Bounced');
      expect(text({'status': 'NOT_SENT', 'error': ''}), 'Not sent');

      final participant = EmailParticipant.fromJson(customer('Ann', {'status': 'QUEUED'}));
      expect(participant.delivery?.status, 'QUEUED');
      expect(EmailParticipant.fromJson(customer('Kim', null)).delivery, isNull);
    });

    test('the snackbar after a send says the email is on its way, or why not all of it is', () {
      String outcome(String status, [String? error]) => emailOutcomeMessage(
          EmailMessage.fromJson({..._staffEmail(1), 'status': status, 'error': error}));
      expect(outcome('QUEUED'), 'Email queued for sending');
      expect(outcome('SENDING'), 'Email queued for sending');
      expect(outcome('SENT'), 'Email sent');
      expect(outcome('NOT_SENT', 'Jane Doe has not connected Gmail'),
          'Email saved — not sent: Jane Doe has not connected Gmail');
      expect(outcome('NOT_SENT'), 'Email saved — not sent');
      expect(outcome('PARTIAL', '1 of 2 not delivered: Bounced'),
          'Email partly sent: 1 of 2 not delivered: Bounced');
      expect(outcome('FAILED', 'Gmail is unavailable (503)'),
          'Email failed: Gmail is unavailable (503)');
    });

    test('a view asks again quickly while mail is on its way, slowly while it is followed', () {
      final now = DateTime.utc(2026, 9, 20, 12);
      EmailMessage email(String status, {Map<String, dynamic>? delivery}) =>
          EmailMessage.fromJson({
            ..._staffEmail(1),
            'status': status,
            'to': [customer('Ann', delivery)],
          });
      Duration? every(List<EmailMessage> emails) => emailRefreshInterval(emails, now: now);
      final recent = now.subtract(const Duration(hours: 23)).toIso8601String();
      final dayOld = now.subtract(const Duration(hours: 25)).toIso8601String();

      expect(every([email('QUEUED')]), const Duration(seconds: 5));
      expect(every([email('SENDING')]), const Duration(seconds: 5));
      expect(every([email('PARTIAL', delivery: {'status': 'SENDING'})]),
          const Duration(seconds: 5));
      expect(every([email('SENT', delivery: {'status': 'SENT', 'sentAt': recent})]),
          const Duration(seconds: 30));
      expect(every([email('SENT', delivery: {'status': 'DELIVERED', 'sentAt': recent})]),
          const Duration(seconds: 30));
      // Any email on its way sets the pace for the whole page.
      expect(
          every([
            email('SENT', delivery: {'status': 'DELIVERED', 'sentAt': recent}),
            email('QUEUED'),
          ]),
          const Duration(seconds: 5));
      // Nothing left to follow: older than a day, or settled.
      expect(every([email('SENT', delivery: {'status': 'DELIVERED', 'sentAt': dayOld})]), isNull);
      expect(every([email('SENT', delivery: {'status': 'READ', 'sentAt': recent})]), isNull);
      expect(every([email('FAILED', delivery: {'status': 'BOUNCED', 'sentAt': recent})]), isNull);
      expect(every([email('SENT')]), isNull);
      expect(every([]), isNull);
    });

    testWidgets('the tab asks again every 5 s while mail is on its way, every 30 s while it is '
        'followed, and stops', (tester) async {
      final recent = DateTime.now().toUtc().subtract(const Duration(hours: 1)).toIso8601String();
      final old = DateTime.now().toUtc().subtract(const Duration(days: 2)).toIso8601String();
      var stage = 'queued';
      Map<String, dynamic> email() => {
            ..._staffEmail(1),
            'error': null,
            'canRetry': false,
            ...switch (stage) {
              'queued' => {
                  'status': 'QUEUED',
                  'to': [customer('Ann', {'status': 'QUEUED'})],
                },
              'followed' => {
                  'status': 'SENT',
                  'sentAt': recent,
                  'to': [
                    customer('Ann',
                        {'status': 'DELIVERED', 'sentAt': recent, 'deliveredAt': recent}),
                  ],
                },
              _ => {
                  'status': 'SENT',
                  'sentAt': old,
                  'to': [
                    customer('Ann', {'status': 'DELIVERED', 'sentAt': old, 'deliveredAt': old}),
                  ],
                },
            },
          };
      final requests = await _pump(
        tester,
        user: _user({Privileges.emailView}),
        pages: (_) => _page([email()]),
      );
      int loads() => requests.where((r) => r.path == '/api/emails').length;
      Future<void> wait(Duration d) async {
        await tester.pump(d);
        // The request goes out, and its answer lands: Dio takes a moment of the clock for each.
        await tester.pump(const Duration(milliseconds: 1));
        await tester.pump(const Duration(milliseconds: 1));
      }

      expect(loads(), 1);
      expect(find.text('Queued'), findsOneWidget);
      await wait(const Duration(seconds: 4));
      expect(loads(), 1);

      stage = 'followed';
      await wait(const Duration(seconds: 1));
      expect(loads(), 2);
      expect(find.text('Sent'), findsOneWidget);
      await wait(const Duration(seconds: 29));
      expect(loads(), 2);

      stage = 'old';
      await wait(const Duration(seconds: 1));
      expect(loads(), 3);
      await wait(const Duration(minutes: 5));
      expect(loads(), 3);

      // The Refresh button still asks, whenever it is pressed.
      await tester.tap(find.byTooltip('Refresh'));
      await wait(Duration.zero);
      expect(loads(), 4);
    });

    testWidgets('the tab stops asking once it is gone', (tester) async {
      final requests = await _pump(
        tester,
        user: _user({Privileges.emailView}),
        pages: (_) => _page([
          {..._staffEmail(1), 'status': 'QUEUED', 'error': null, 'canRetry': false},
        ]),
      );
      int loads() => requests.where((r) => r.path == '/api/emails').length;
      await tester.pump(const Duration(seconds: 5));
      await tester.pump(const Duration(milliseconds: 1));
      expect(loads(), 2);

      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(minutes: 1));
      expect(loads(), 2);
    });
  });
}

/// The tab's own list; every SelectableText inside it scrolls too.
final _list = find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first;

/// A person on the card, matched by how their runs read together: the name, the address (split
/// after its "@") and each note are separate Text widgets.
Finder _person(String line) => find.byElementPredicate((element) {
      if (element.widget is! MergeSemantics) return false;
      final runs = <String>[];
      void visit(Element e) {
        final widget = e.widget;
        if (widget is Text) {
          runs.add(widget.data ?? '');
        } else {
          e.visitChildren(visit);
        }
      }

      element.visitChildren(visit);
      return runs.join(' ').replaceAll('@ ', '@') == line;
    }, description: 'person "$line"');
