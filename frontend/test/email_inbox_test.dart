import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_actions.dart';
import 'package:gene_invoice/features/email/email_models.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/email/inbox_screen.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/roboto.dart';

CurrentUser _user(Set<String> privileges, {int? customerId}) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
    );

const _staffPrivileges = {
  Privileges.notificationView,
  Privileges.invoiceView,
  Privileges.emailView,
  Privileges.emailSend,
};

Future<bool Function()> _pumpShell(WidgetTester tester, Size size, CurrentUser user) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  var polled = false;
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      ShellRoute(
        builder: (context, state, child) => AppShell(child: child),
        routes: [
          GoRoute(path: '/', builder: (_, __) => const Text('dashboard page')),
          GoRoute(path: '/inbox', builder: (_, __) => const Text('inbox page')),
          GoRoute(path: '/invoices', builder: (_, __) => const Text('invoices page')),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      currentUserProvider.overrideWithValue(user),
      unreadCountProvider.overrideWith((ref) => Stream.value(0)),
      inboxUnreadCountProvider.overrideWith((ref) {
        polled = true;
        return Stream.value(4);
      }),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return () => polled;
}

Map<String, dynamic> _email({bool? canOpenRecord = true}) => {
      'id': 91,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'entityLink': '/invoices/42',
      'direction': 'INBOUND',
      'status': 'RECEIVED',
      'subject': 'Re: Payment reminder',
      'body': 'Paid yesterday.',
      'from': {
        'name': 'Acme Ltd',
        'address': 'ap@acme.com',
        'internal': false,
        'masked': false,
        'sources': [],
      },
      'to': [
        {
          'name': 'Jane Doe',
          'address': 'jane@company.com',
          'userId': 3,
          'internal': true,
          'masked': false,
          'sources': [
            {'type': 'MAILBOX'},
          ],
        },
      ],
      'cc': [],
      'unresolved': [],
      'sentBy': null,
      'attempts': 0,
      'occurredAt': '2026-09-17T11:00:00Z',
      'canRetry': false,
      if (canOpenRecord != null) 'canOpenRecord': canOpenRecord,
      'readByMe': false,
    };

/// [held] keeps a request ("METHOD /path") from being answered until its future completes.
/// [email] replaces the email the reader loads; [delivery] what GET /api/emails/delivery says.
Future<List<RequestOptions>> _pumpInbox(
  WidgetTester tester, {
  CurrentUser? user,
  bool? canOpenRecord = true,
  Map<String, Future<void>> held = const {},
  Map<String, dynamic>? email,
  Map<String, dynamic> delivery = const {'configured': false, 'gmail': null},
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final requests = <RequestOptions>[];
  final routes = <String, Object Function()>{
    'GET /api/table-schemas/inbox': () => {'entity': 'inbox', 'columns': []},
    'GET /api/emails/delivery': () => delivery,
    'GET /api/inbox': () => {
          'content': [
            {
              'id': 501,
              'emailId': 91,
              'entityType': 'INVOICE',
              'entityId': 42,
              'entityLabel': 'Invoice INV-0042',
              'entityLink': '/invoices/42',
              'subject': 'Re: Payment reminder',
              'snippet': 'Paid yesterday.',
              'from': {'name': 'Acme Ltd', 'address': 'ap@acme.com', 'masked': false},
              'direction': 'INBOUND',
              'status': 'RECEIVED',
              'occurredAt': '2026-09-17T11:00:00Z',
              'read': false,
            },
          ],
          'page': 0,
          'size': 20,
          'totalElements': 1,
          'totalPages': 1,
        },
    'GET /api/emails/91': () => email ?? _email(canOpenRecord: canOpenRecord),
    'POST /api/inbox/501/read': () => <String, dynamic>{},
    'POST /api/inbox/mark-all-read': () => {'updated': 1},
    'GET /api/inbox/unread-count': () => {'count': 0},
  };
  final dio = Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
      requests.add(options);
      final key = '${options.method} ${options.path}';
      final gate = held[key];
      if (gate != null) await gate;
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: routes[key]?.call()));
    }));

  final router = GoRouter(
    initialLocation: '/inbox',
    routes: [
      GoRoute(
          path: '/inbox',
          builder: (_, __) => const InboxScreen(query: TableQuery(sort: 'occurredAt,desc'))),
      GoRoute(path: '/invoices/:id', builder: (_, s) => Text('invoice ${s.pathParameters['id']}')),
      GoRoute(path: '/me/gmail', builder: (_, __) => const Text('gmail page')),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(dio),
      currentUserProvider.overrideWithValue(user ?? _user(_staffPrivileges)),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return requests;
}

List<String> _posts(List<RequestOptions> requests) =>
    requests.where((r) => r.method == 'POST').map((r) => r.path).toList();

/// Keeps the Inbox badge watched, as the sidebar does, and counts how often it has asked.
int Function() _watchBadge(WidgetTester tester, List<RequestOptions> requests) {
  ProviderScope.containerOf(tester.element(find.byType(InboxScreen)))
      .listen(inboxUnreadCountProvider, (_, __) {});
  return () => requests.where((r) => r.path == '/api/inbox/unread-count').length;
}

/// Answers every unread count with [count], or fails it while [failing] says so.
Dio _countsDio({required int count, required bool Function() failing, List<String>? asked}) =>
    Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        asked?.add(options.path);
        if (failing()) {
          handler.reject(DioException(requestOptions: options, message: 'Connection refused'));
        } else {
          handler.resolve(Response(requestOptions: options, statusCode: 200, data: {'count': count}));
        }
      }));

/// A person in the reader, matched by how their runs read together: the name, the address (split
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

void main() {
  // Real glyph widths, for the reader's line breaks at phone width. Nothing else here measures
  // text.
  setUpAll(loadRoboto);

  testWidgets('on a phone Inbox sits right below Dashboard in the drawer, with its count',
      (tester) async {
    final polled = await _pumpShell(tester, const Size(400, 820), _user(_staffPrivileges));

    await tester.tap(find.byTooltip('Open navigation menu'));
    await tester.pumpAndSettle();
    final drawer = find.byType(Drawer);
    double top(String label) =>
        tester.getTopLeft(find.descendant(of: drawer, matching: find.text(label))).dy;
    expect(top('Dashboard'), lessThan(top('Inbox')));
    expect(top('Inbox'), lessThan(top('Invoices')));
    expect(find.descendant(of: drawer, matching: find.text('4')), findsOneWidget);
    expect(polled(), isTrue);

    await tester.tap(find.descendant(of: drawer, matching: find.text('Inbox')));
    await tester.pumpAndSettle();
    expect(find.text('inbox page'), findsOneWidget);
  });

  testWidgets('on a desktop the rail shows the Inbox icon second, with its count', (tester) async {
    await _pumpShell(tester, const Size(1366, 900), _user(_staffPrivileges));

    double top(IconData icon) => tester.getTopLeft(find.byIcon(icon)).dy;
    expect(top(Icons.dashboard_outlined), lessThan(top(Icons.inbox_outlined)));
    expect(top(Icons.inbox_outlined), lessThan(top(Icons.receipt_long_outlined)));
    expect(find.text('4'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a customer login with EMAIL_VIEW keeps the Inbox', (tester) async {
    await _pumpShell(tester, const Size(1366, 900),
        _user({Privileges.invoiceView, Privileges.emailView}, customerId: 5));
    expect(find.byIcon(Icons.inbox_outlined), findsOneWidget);
  });

  testWidgets('without EMAIL_VIEW there is no Inbox, and nothing polls its count',
      (tester) async {
    final polled = await _pumpShell(
        tester, const Size(1366, 900), _user({Privileges.invoiceView, Privileges.emailSend}));
    expect(find.byIcon(Icons.inbox_outlined), findsNothing);
    expect(polled(), isFalse);
  });

  testWidgets('opening an inbox row reads it, marks it read and leads to its record',
      (tester) async {
    final requests = await _pumpInbox(tester);
    final listed = requests.where((r) => r.path == '/api/inbox').length;

    expect(find.text('Email delivery is not configured'), findsOneWidget);
    final subject = tester.widget<Text>(find.text('Re: Payment reminder'));
    expect(subject.style?.fontWeight, FontWeight.w700);

    await tester.tap(find.text('Re: Payment reminder'));
    await tester.pumpAndSettle();
    expect(_posts(requests), ['/api/inbox/501/read']);
    // The row behind the reader refreshes, so it no longer reads as unread.
    expect(requests.where((r) => r.path == '/api/inbox').length, greaterThan(listed));
    expect(find.text('Paid yesterday.'), findsOneWidget);
    expect(_person('Jane Doe <jane@company.com> — mailbox'), findsOneWidget);

    await tester.tap(find.text('Open Invoice INV-0042'));
    await tester.pumpAndSettle();
    expect(find.text('invoice 42'), findsOneWidget);
  });

  testWidgets('the reader leads to the record only when the email says this reader may open it',
      (tester) async {
    for (final canOpenRecord in [false, null]) {
      await _pumpInbox(tester, canOpenRecord: canOpenRecord);
      await tester.tap(find.text('Re: Payment reminder'));
      await tester.pumpAndSettle();
      expect(find.text('Paid yesterday.'), findsOneWidget);
      expect(find.text('Open Invoice INV-0042'), findsNothing);
      expect(find.text('Close'), findsOneWidget);
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();
    }
    expect(EmailMessage.fromJson(_email(canOpenRecord: null)).canOpenRecord, isFalse);
    expect(EmailMessage.fromJson(_email()).canOpenRecord, isTrue);
  });

  testWidgets('the reader tells its reader they received the email, whichever way it went',
      (tester) async {
    // An email staff sent, which reached this user's Inbox as one of its recipients.
    await _pumpInbox(tester, email: {
      ..._email(),
      'direction': 'OUTBOUND',
      'status': 'SENT',
      'occurredAt': '2026-09-17T05:54:00Z',
      'sentAt': '2026-09-17T05:54:02Z',
    });
    await tester.tap(find.text('Re: Payment reminder'));
    await tester.pumpAndSettle();

    final reader = find.byType(AlertDialog);
    final received = 'Received ${formatDateTime('2026-09-17T05:54:00Z')}';
    expect(find.descendant(of: reader, matching: find.text(received)), findsOneWidget);
    expect(find.descendant(of: reader, matching: find.byIcon(Icons.call_received)), findsOneWidget);
    expect(find.descendant(of: reader, matching: find.textContaining('Outgoing')), findsNothing);
    expect(find.descendant(of: reader, matching: find.byIcon(Icons.call_made)), findsNothing);
  });

  testWidgets('on a phone the reader breaks lines between a name and its address, not inside it',
      (tester) async {
    await _pumpInbox(tester, size: const Size(400, 860), email: {
      ..._email(),
      'from': {
        'name': 'e2eui Cust mu4ryb3o',
        'address': 'e2eui.cust.mu4ryb3o@example.com',
        'internal': false,
        'masked': false,
        'sources': [],
      },
      'to': [
        {
          'name': 'System Administrator',
          'address': 'admin@geneinvoice.local',
          'userId': 3,
          'internal': true,
          'masked': false,
          'sources': [
            {'type': 'ROLE', 'role': 'SALES_POC', 'label': 'Sales POC'},
          ],
        },
      ],
    });
    await tester.tap(find.text('Re: Payment reminder'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);

    final reader = tester.getRect(find.byType(AlertDialog));
    Rect run(String text) {
      final found = find.descendant(of: find.byType(AlertDialog), matching: find.text(text));
      expect(found, findsOneWidget, reason: text);
      final rect = tester.getRect(found);
      expect(reader.left <= rect.left && rect.right <= reader.right, isTrue,
          reason: '"$text" at $rect leaves the reader at $reader');
      return rect;
    }

    // The name and the address are separate runs, each whole on its line: the address, too long
    // to follow the name, starts a line of its own, and breaks again only after its "@".
    final name = run('e2eui Cust mu4ryb3o');
    final local = run('<e2eui.cust.mu4ryb3o@');
    final domain = run('example.com>');
    final line = name.height;
    expect(local.top, greaterThanOrEqualTo(name.bottom));
    expect(domain.top, greaterThanOrEqualTo(local.bottom));
    expect([local.height, domain.height], everyElement(line));
    expect(_person('e2eui Cust mu4ryb3o <e2eui.cust.mu4ryb3o@example.com>'), findsOneWidget);

    final admin = run('System Administrator');
    expect(admin.height, line);
    expect(_person('System Administrator <admin@geneinvoice.local> — Sales POC'), findsOneWidget);
    for (final text in ['<admin@', 'geneinvoice.local>', '— Sales POC']) {
      expect(run(text).height, lessThanOrEqualTo(line), reason: text);
    }
  });

  testWidgets('reading an email and leaving for its record at once still updates the badge',
      (tester) async {
    final answer = Completer<void>();
    final requests = await _pumpInbox(tester, held: {'POST /api/inbox/501/read': answer.future});
    final badgeFetches = _watchBadge(tester, requests);
    await tester.pumpAndSettle();
    final before = badgeFetches();

    await tester.tap(find.text('Re: Payment reminder'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Open Invoice INV-0042'));
    await tester.pumpAndSettle();
    expect(find.text('invoice 42'), findsOneWidget);

    answer.complete();
    await tester.pumpAndSettle();
    await tester.pump(Duration.zero);
    expect(badgeFetches(), before + 1);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('leaving the inbox while "Mark all as read" is out still updates the badge',
      (tester) async {
    final answer = Completer<void>();
    final requests =
        await _pumpInbox(tester, held: {'POST /api/inbox/mark-all-read': answer.future});
    final badgeFetches = _watchBadge(tester, requests);
    await tester.pumpAndSettle();
    final before = badgeFetches();

    await tester.tap(find.text('Mark all as read'));
    await tester.pump();
    GoRouter.of(tester.element(find.byType(InboxScreen))).go('/invoices/42');
    await tester.pumpAndSettle();
    expect(find.byType(InboxScreen), findsNothing);

    answer.complete();
    await tester.pumpAndSettle();
    await tester.pump(Duration.zero);
    expect(badgeFetches(), before + 1);
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('a failed first count does not stop the Inbox badge or the bell from polling',
      (tester) async {
    var failing = true;
    final asked = <String>[];
    final container = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(_countsDio(count: 2, failing: () => failing, asked: asked)),
      currentUserProvider.overrideWithValue(_user(_staffPrivileges)),
    ]);
    final inbox = container.listen(inboxUnreadCountProvider, (_, __) {});
    final bell = container.listen(unreadCountProvider, (_, __) {});
    await tester.pump(Duration.zero);
    expect(asked, hasLength(2));
    expect(inbox.read().hasValue, isFalse);
    expect(bell.read().hasValue, isFalse);

    failing = false;
    await tester.pump(const Duration(seconds: 30));
    expect(inbox.read().valueOrNull, 2);
    expect(bell.read().valueOrNull, 2);
    container.dispose();
  });

  testWidgets('once nobody is signed in the Inbox badge asks nothing more', (tester) async {
    final signedIn = StateProvider<CurrentUser?>((ref) => _user(_staffPrivileges));
    final asked = <String>[];
    final container = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(_countsDio(count: 2, failing: () => false, asked: asked)),
      currentUserProvider.overrideWith((ref) => ref.watch(signedIn)),
    ]);
    container.listen(inboxUnreadCountProvider, (_, __) {});
    await tester.pump(Duration.zero);
    expect(asked, ['/api/inbox/unread-count']);

    // Signing out: the token is gone, so any request now would come back 401 and sign out again.
    container.read(signedIn.notifier).state = null;
    await tester.pump();
    await tester.pump(const Duration(minutes: 2));
    expect(asked, ['/api/inbox/unread-count']);
    container.dispose();
  });

  testWidgets('staff sort by sender; a customer login is not offered it, which the server refuses',
      (tester) async {
    // Columns in order: From, Subject, About, Received, Status.
    // The first table; the row actions have one of their own beside it.
    List<DataColumn> columns() =>
        tester.widget<DataTable>(find.byType(DataTable).first).columns;

    await _pumpInbox(tester);
    expect(columns()[0].onSort, isNotNull);

    await _pumpInbox(tester,
        user: _user({Privileges.emailView, Privileges.emailSend}, customerId: 9));
    expect(columns()[0].onSort, isNull);
    expect(columns()[1].onSort, isNotNull);
  });

  testWidgets('the banner says why mail may not be going out or coming in, and leads to Gmail',
      (tester) async {
    Map<String, dynamic> gmail(String status, {String? lastSyncError}) => {
          'status': status,
          'gmailAddress': status == 'NOT_CONNECTED' ? null : 'jane@gmail.com',
          'reason': status == 'NEEDS_RECONNECT' ? 'Reconnect Gmail.' : null,
          'lastSyncedAt': status == 'CONNECTED' ? '2026-09-20T11:00:00Z' : null,
          'lastSyncError': lastSyncError,
        };
    for (final (delivery, message, action) in <(Map<String, dynamic>, String?, String?)>[
      ({'configured': false, 'gmail': null}, 'Email delivery is not configured', null),
      (
        {'configured': true, 'gmail': gmail('NOT_CONNECTED')},
        'Connect your Gmail to send email and receive replies',
        'Connect',
      ),
      (
        {'configured': true, 'gmail': gmail('NEEDS_RECONNECT')},
        'Your Gmail connection needs to be renewed',
        'Reconnect',
      ),
      (
        {
          'configured': true,
          'gmail': gmail('CONNECTED', lastSyncError: 'Gmail is unavailable (503)'),
        },
        'The last Gmail check failed: Gmail is unavailable (503)',
        null,
      ),
      // All well; and a customer login, who has no Gmail of their own.
      ({'configured': true, 'gmail': gmail('CONNECTED')}, null, null),
      ({'configured': true, 'gmail': null}, null, null),
    ]) {
      await _pumpInbox(tester, delivery: delivery);
      final reason = '$delivery';
      for (final text in [
        'Email delivery is not configured',
        'Connect your Gmail to send email and receive replies',
        'Your Gmail connection needs to be renewed',
      ]) {
        expect(find.text(text), text == message ? findsOneWidget : findsNothing, reason: reason);
      }
      expect(find.textContaining('The last Gmail check failed'),
          message != null && message.startsWith('The last') ? findsOneWidget : findsNothing,
          reason: reason);
      for (final label in ['Connect', 'Reconnect']) {
        expect(find.widgetWithText(TextButton, label),
            label == action ? findsOneWidget : findsNothing,
            reason: reason);
      }
      if (action != null) {
        await tester.tap(find.widgetWithText(TextButton, action));
        await tester.pumpAndSettle();
        expect(find.text('gmail page'), findsOneWidget, reason: reason);
      }
    }
  });

  testWidgets('a reader who may not send email is never asked to connect a Gmail', (tester) async {
    // The server's word on their Gmail may be "not connected", but the page Connect leads to
    // turns them away: the seeded VIEWER (EMAIL_VIEW only), and a customer login that may send.
    Map<String, dynamic> gmail(String status, {String? lastSyncError}) => {
          'status': status,
          'gmailAddress': null,
          'reason': status == 'NEEDS_RECONNECT' ? 'Reconnect Gmail.' : null,
          'lastSyncedAt': null,
          'lastSyncError': lastSyncError,
        };
    for (final user in [
      _user({Privileges.emailView}),
      _user({Privileges.emailView, Privileges.emailSend}, customerId: 5),
    ]) {
      for (final g in [
        gmail('NOT_CONNECTED'),
        gmail('NEEDS_RECONNECT'),
        gmail('CONNECTED', lastSyncError: 'Gmail is unavailable (503)'),
      ]) {
        final reason = '${user.privileges} ${user.customerId} $g';
        await _pumpInbox(tester, user: user, delivery: {'configured': true, 'gmail': g});
        expect(find.textContaining('Gmail'), findsNothing, reason: reason);
        expect(find.widgetWithText(TextButton, 'Connect'), findsNothing, reason: reason);
        expect(find.widgetWithText(TextButton, 'Reconnect'), findsNothing, reason: reason);
      }
      // They still hear that no mail leaves the app at all.
      await _pumpInbox(tester,
          user: user, delivery: {'configured': false, 'gmail': gmail('NOT_CONNECTED')});
      expect(find.text('Email delivery is not configured'), findsOneWidget);
      expect(find.widgetWithText(TextButton, 'Connect'), findsNothing);
    }
  });

  testWidgets('the reader follows an email on its way out, and stops once closed', (tester) async {
    final recent = DateTime.now().toUtc().subtract(const Duration(minutes: 2)).toIso8601String();
    // Mutable: the next time the reader asks, the server says what has become of it.
    final email = {
      ..._email(),
      'direction': 'OUTBOUND',
      'status': 'QUEUED',
      'to': [
        {
          'name': 'Jane Doe',
          'address': 'jane@company.com',
          'userId': 3,
          'internal': true,
          'masked': false,
          'sources': [
            {'type': 'USER'},
          ],
          'delivery': {'status': 'QUEUED'},
        },
      ],
    };
    final requests = await _pumpInbox(tester, email: email);
    int loads() => requests.where((r) => r.path == '/api/emails/91').length;
    Future<void> wait(Duration d) async {
      await tester.pump(d);
      // The request goes out, and its answer lands: Dio takes a moment of the clock for each.
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 1));
    }

    await tester.tap(find.text('Re: Payment reminder'));
    await tester.pumpAndSettle();
    expect(loads(), 1);
    expect(_person('Jane Doe <jane@company.com> — added directly · Queued'), findsOneWidget);

    email['status'] = 'SENT';
    email['sentAt'] = recent;
    (email['to'] as List).first['delivery'] = {'status': 'SENT', 'sentAt': recent};
    await wait(const Duration(seconds: 5));
    expect(loads(), 2);
    expect(
        _person('Jane Doe <jane@company.com> — added directly · Sent ${formatDateTime(recent)}'),
        findsOneWidget);
    // Followed more slowly now that it has gone.
    await wait(const Duration(seconds: 25));
    expect(loads(), 2);
    await wait(const Duration(seconds: 5));
    expect(loads(), 3);

    await tester.tap(find.text('Close'));
    await tester.pumpAndSettle();
    await wait(const Duration(minutes: 2));
    expect(loads(), 3);
  });

  testWidgets('mark all as read posts once and refreshes the list', (tester) async {
    final requests = await _pumpInbox(tester);
    final before = requests.where((r) => r.path == '/api/inbox').length;

    await tester.tap(find.text('Mark all as read'));
    await tester.pumpAndSettle();
    expect(_posts(requests), ['/api/inbox/mark-all-read']);
    expect(requests.where((r) => r.path == '/api/inbox').length, greaterThan(before));
  });

  testWidgets('the notify checkbox shows only to users who may send email', (tester) async {
    Future<void> pump(Set<String> privileges) async {
      var value = false;
      await tester.pumpWidget(ProviderScope(
        overrides: [currentUserProvider.overrideWithValue(_user(privileges))],
        child: MaterialApp(
          home: Scaffold(
            body: StatefulBuilder(
              builder: (context, setState) => Column(children: [
                NotifyByEmailCheckbox(value: value, onChanged: (v) => setState(() => value = v)),
                Text('notify: $value'),
              ]),
            ),
          ),
        ),
      ));
      await tester.pumpAndSettle();
    }

    await pump({Privileges.emailView});
    expect(find.text('Notify through email'), findsNothing);
    expect(find.byType(Checkbox), findsNothing);

    await pump({Privileges.emailView, Privileges.emailSend});
    expect(find.text('Notify through email'), findsOneWidget);
    await tester.tap(find.text('Notify through email'));
    await tester.pumpAndSettle();
    expect(find.text('notify: true'), findsOneWidget);
  });

  testWidgets('list and details actions appear only for users who may send', (tester) async {
    Future<void> pump(Set<String> privileges) => tester.pumpWidget(ProviderScope(
          overrides: [currentUserProvider.overrideWithValue(_user(privileges))],
          child: MaterialApp(
            home: Scaffold(
              body: Consumer(
                builder: (context, ref, _) {
                  final page = sendEmailPageAction(context, ref, type: EmailEntityType.invoice);
                  final header = sendEmailHeaderButton(context, ref,
                      type: EmailEntityType.invoice, entityId: 42);
                  return Column(children: [
                    sendEmailRowAction(context, type: EmailEntityType.invoice, entityId: 42),
                    Text('page: ${page != null}, header: ${header != null}'),
                  ]);
                },
              ),
            ),
          ),
        ));

    await pump({Privileges.emailView});
    expect(find.byTooltip('Send email'), findsNothing);
    expect(find.text('page: false, header: false'), findsOneWidget);

    await pump({Privileges.emailSend});
    expect(find.byTooltip('Send email'), findsOneWidget);
    expect(find.text('page: true, header: true'), findsOneWidget);
  });
}
