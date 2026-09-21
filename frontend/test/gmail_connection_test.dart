import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/gmail_connection_screen.dart';
import 'package:gene_invoice/features/notifications/notifications_providers.dart';
import 'package:gene_invoice/features/users/user_detail_screen.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/app_shell.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

CurrentUser _user(Set<String> privileges, {int? customerId}) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
    );

final _staff = _user({Privileges.emailView, Privileges.emailSend});

const _connectedAt = '2026-09-20T10:00:00Z';
const _checkedAt = '2026-09-20T11:30:00Z';

Map<String, dynamic> _gmail({
  bool configured = true,
  String status = 'NOT_CONNECTED',
  String? gmailAddress,
  String? clientId,
  String? reason,
  String? connectedAt,
  String? lastSyncedAt,
  String? lastSyncError,
  String? serviceError,
}) =>
    {
      'configured': configured,
      'status': status,
      'gmailAddress': gmailAddress,
      'clientId': clientId,
      'reason': reason,
      'connectedAt': connectedAt,
      'lastSyncedAt': lastSyncedAt,
      'lastSyncError': lastSyncError,
      'serviceError': serviceError,
    };

Map<String, dynamic> _connected({String? lastSyncError}) => _gmail(
      status: 'CONNECTED',
      gmailAddress: 'jane@gmail.com',
      clientId: '123-abc.apps.googleusercontent.com',
      connectedAt: _connectedAt,
      lastSyncedAt: _checkedAt,
      lastSyncError: lastSyncError,
    );

FakeBackend _backend(Map<String, dynamic> initial, {Object? Function(RequestOptions)? onPut}) {
  var current = initial;
  return FakeBackend({
    'GET /api/me/gmail': (_) => current,
    'PUT /api/me/gmail': onPut ??
        (_) {
          current = _connected();
          return current;
        },
    'DELETE /api/me/gmail': (_) {
      current = _gmail();
      return null;
    },
  });
}

Future<void> _pumpScreen(
  WidgetTester tester,
  FakeBackend backend, {
  CurrentUser? user,
  Size size = const Size(1366, 1600),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user ?? _staff),
    ],
    child: MaterialApp(theme: AppTheme.light(), home: const GmailConnectionScreen()),
  ));
  await tester.pumpAndSettle();
}

Finder _field(String label) => find.widgetWithText(TextFormField, label);

EditableText _editable(WidgetTester tester, String label) => tester.widget<EditableText>(
    find.descendant(of: _field(label), matching: find.byType(EditableText)));

FilledButton _filled(WidgetTester tester, String label) => tester.widget<FilledButton>(find
    .ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is FilledButton)));

const _stepFive = 'In Step 1 enter https://www.googleapis.com/auth/gmail.send '
    'https://www.googleapis.com/auth/gmail.readonly, click Authorize APIs, sign in with your '
    'Gmail and allow access (on "Google hasn\'t verified this app" choose Continue).';
const _sevenDays = 'While the Google app is in Testing, Google expires the refresh token after '
    '7 days; repeat steps 4–7 and click Reconnect. Personal Gmail sends at most about 500 '
    'messages a day, and each copy counts.';

void main() {
  group('the Gmail connection page', () {
    testWidgets('not connected: it asks for all three values, and shows how to get them',
        (tester) async {
      final backend = _backend(_gmail());
      await _pumpScreen(tester, backend);

      expect(find.text('Gmail connection'), findsOneWidget);
      expect(find.text('Not connected'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Connect'), findsOneWidget);
      expect(find.text('Reconnect'), findsNothing);
      expect(find.text('Disconnect'), findsNothing);
      expect(find.text(_stepFive), findsOneWidget);
      expect(find.text(_sevenDays), findsOneWidget);
      expect(find.textContaining('Exchange authorization code for tokens'), findsOneWidget);

      await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
      await tester.pumpAndSettle();
      expect(find.text('Enter the client ID'), findsOneWidget);
      expect(find.text('Enter the client secret'), findsOneWidget);
      expect(find.text('Enter the refresh token'), findsOneWidget);
      expect(backend.sent('PUT /api/me/gmail'), isEmpty);

      await tester.enterText(_field('Client ID'), '   ');
      await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
      await tester.pumpAndSettle();
      expect(find.text('Enter the client ID'), findsOneWidget);
      expect(backend.sent('PUT /api/me/gmail'), isEmpty);
    });

    testWidgets('the secret and the token are hidden until asked for', (tester) async {
      await _pumpScreen(tester, _backend(_gmail()));

      expect(_editable(tester, 'Client ID').obscureText, isFalse);
      expect(_editable(tester, 'Client secret').obscureText, isTrue);
      expect(_editable(tester, 'Refresh token').obscureText, isTrue);

      await tester.tap(find.byTooltip('Show client secret'));
      await tester.pumpAndSettle();
      expect(_editable(tester, 'Client secret').obscureText, isFalse);
      expect(_editable(tester, 'Refresh token').obscureText, isTrue);
      await tester.tap(find.byTooltip('Hide client secret'));
      await tester.tap(find.byTooltip('Show refresh token'));
      await tester.pumpAndSettle();
      expect(_editable(tester, 'Client secret').obscureText, isTrue);
      expect(_editable(tester, 'Refresh token').obscureText, isFalse);
    });

    testWidgets('connecting sends the three values, trimmed, and shows the new status',
        (tester) async {
      final backend = _backend(_gmail());
      await _pumpScreen(tester, backend);

      await tester.enterText(_field('Client ID'), '  123-abc.apps.googleusercontent.com ');
      await tester.enterText(_field('Client secret'), 'GOCSPX-secret ');
      await tester.enterText(_field('Refresh token'), ' 1//0g-token');
      await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
      await tester.pumpAndSettle();

      expect(backend.sent('PUT /api/me/gmail').single.data, {
        'clientId': '123-abc.apps.googleusercontent.com',
        'clientSecret': 'GOCSPX-secret',
        'refreshToken': '1//0g-token',
      });
      expect(find.text('Gmail connected as jane@gmail.com'), findsOneWidget);
      expect(backend.sent('GET /api/me/gmail'), hasLength(2));
      expect(find.text('Connected as jane@gmail.com since ${formatDateTime(_connectedAt)}'),
          findsOneWidget);
      expect(find.text('Last checked for replies ${formatDateTime(_checkedAt)}'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Reconnect'), findsOneWidget);
      expect(find.text('Disconnect'), findsOneWidget);
      expect(_editable(tester, 'Client secret').controller.text, isEmpty);
      expect(_editable(tester, 'Refresh token').controller.text, isEmpty);
      expect(_editable(tester, 'Client ID').controller.text, '123-abc.apps.googleusercontent.com');
    });

    testWidgets('Google\'s refusal shows under the form, a field\'s under that field',
        (tester) async {
      const refused = 'Google did not accept the refresh token (invalid_grant: Bad Request). Make '
          'sure it was made with this client ID and secret, and has not expired or been revoked.';
      Object? answer = const FakeFailure(
          400, {'status': 400, 'error': 'Bad Request', 'message': refused});
      final backend = _backend(_gmail(), onPut: (_) => answer);
      await _pumpScreen(tester, backend);

      Future<void> connect() async {
        await tester.enterText(_field('Client ID'), 'cid.apps.googleusercontent.com');
        await tester.enterText(_field('Client secret'), 'secret');
        await tester.enterText(_field('Refresh token'), 'token');
        await tester.tap(find.widgetWithText(FilledButton, 'Connect'));
        await tester.pumpAndSettle();
      }

      await connect();
      expect(find.text(refused), findsOneWidget);
      expect(find.text('Not connected'), findsOneWidget);
      expect(backend.sent('GET /api/me/gmail'), hasLength(1));
      expect(_editable(tester, 'Refresh token').controller.text, 'token');

      answer = const FakeFailure(
          502, {'status': 502, 'message': 'Could not reach Google: timed out'});
      await connect();
      expect(find.text(refused), findsNothing);
      expect(find.text('Could not reach Google: timed out'), findsOneWidget);

      answer = const FakeFailure(400, {
        'status': 400,
        'message': 'One or more fields are invalid',
        'fieldErrors': {'refreshToken': 'Refresh token is too long'},
      });
      await connect();
      expect(find.text('Refresh token is too long'), findsOneWidget);
      expect(find.text('Could not reach Google: timed out'), findsNothing);
      expect(find.text('One or more fields are invalid'), findsNothing);
      await tester.enterText(_field('Refresh token'), 'shorter');
      await tester.pumpAndSettle();
      expect(find.text('Refresh token is too long'), findsNothing);
    });

    testWidgets('connected: Reconnect with the client ID filled in, and Disconnect asks first',
        (tester) async {
      final backend = _backend(_connected(lastSyncError: 'Gmail is unavailable (503): backend'));
      await _pumpScreen(tester, backend);

      expect(find.text('Connected as jane@gmail.com since ${formatDateTime(_connectedAt)}'),
          findsOneWidget);
      expect(find.text('The last check failed: Gmail is unavailable (503): backend'),
          findsOneWidget);
      expect(_editable(tester, 'Client ID').controller.text, '123-abc.apps.googleusercontent.com');
      expect(_editable(tester, 'Client secret').controller.text, isEmpty);
      expect(find.widgetWithText(FilledButton, 'Reconnect'), findsOneWidget);

      expect(find.text(_sevenDays), findsNothing);
      await tester.tap(find.text('How to get these'));
      await tester.pumpAndSettle();
      expect(find.text(_sevenDays), findsOneWidget);

      await tester.tap(find.text('Disconnect'));
      await tester.pumpAndSettle();
      expect(find.text('Disconnect Gmail?'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(backend.sent('DELETE /api/me/gmail'), isEmpty);
      expect(find.text('Disconnect Gmail?'), findsNothing);

      await tester.tap(find.text('Disconnect'));
      await tester.pumpAndSettle();
      await tester.tap(
          find.descendant(of: find.byType(AlertDialog), matching: find.text('Disconnect')));
      await tester.pumpAndSettle();
      expect(backend.sent('DELETE /api/me/gmail'), hasLength(1));
      expect(find.text('Gmail disconnected'), findsOneWidget);
      expect(find.text('Not connected'), findsOneWidget);
      expect(find.text('Disconnect'), findsNothing);
      expect(find.widgetWithText(FilledButton, 'Connect'), findsOneWidget);
    });

    testWidgets('answered from the app\'s copy, the client ID is filled in once the service is back',
        (tester) async {
      final fromCopy = {
        ..._connected(),
        'clientId': null,
        'serviceError': 'Could not reach the mail service: Connection refused',
      };
      var current = fromCopy;
      final backend = FakeBackend({'GET /api/me/gmail': (_) => current});
      await _pumpScreen(tester, backend);

      expect(find.text('Could not reach the mail service: Connection refused'), findsOneWidget);
      expect(_editable(tester, 'Client ID').controller.text, isEmpty);

      current = _connected();
      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(backend.sent('GET /api/me/gmail'), hasLength(2));
      expect(find.text('Could not reach the mail service: Connection refused'), findsNothing);
      expect(_editable(tester, 'Client ID').controller.text, '123-abc.apps.googleusercontent.com');

      await tester.enterText(_field('Client ID'), 'other.apps.googleusercontent.com');
      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(_editable(tester, 'Client ID').controller.text, 'other.apps.googleusercontent.com');
    });

    testWidgets('a client ID typed while the service was away is not written over when it is back',
        (tester) async {
      var current = {
        ..._connected(),
        'clientId': null,
        'serviceError': 'Could not reach the mail service: Connection refused',
      };
      final backend = FakeBackend({'GET /api/me/gmail': (_) => current});
      await _pumpScreen(tester, backend);

      await tester.enterText(_field('Client ID'), 'typed.apps.googleusercontent.com');
      current = _connected();
      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(_editable(tester, 'Client ID').controller.text, 'typed.apps.googleusercontent.com');
    });

    testWidgets('a connection Google no longer accepts says why, in the error colour',
        (tester) async {
      const reason = 'Google no longer accepts this Gmail connection (invalid_grant: Token has '
          'been expired or revoked.). Reconnect Gmail.';
      await _pumpScreen(
          tester,
          _backend(_gmail(
            status: 'NEEDS_RECONNECT',
            gmailAddress: 'jane@gmail.com',
            clientId: 'cid.apps.googleusercontent.com',
            reason: reason,
            serviceError: 'Could not reach the mail service: Connection refused',
          )));

      expect(find.text('Needs renewing'), findsOneWidget);
      final error = Theme.of(tester.element(find.text('Needs renewing'))).colorScheme.error;
      expect(tester.widget<Text>(find.text(reason)).style?.color, error);
      expect(find.text('Could not reach the mail service: Connection refused'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Reconnect'), findsOneWidget);
      expect(find.text('Disconnect'), findsOneWidget);
    });

    testWidgets('without a mail service it says so, and the form takes nothing', (tester) async {
      await _pumpScreen(tester, _backend(_gmail(configured: false)));

      expect(
          find.text('Email delivery is not configured, so Gmail cannot be connected. Emails are '
              'saved in the app but not sent.'),
          findsOneWidget);
      for (final label in ['Client ID', 'Client secret', 'Refresh token']) {
        final field = find.descendant(of: _field(label), matching: find.byType(TextField));
        expect(tester.widget<TextField>(field).enabled, isFalse, reason: label);
      }
      expect(_filled(tester, 'Connect').onPressed, isNull);
    });

    testWidgets('a customer login has no Gmail to connect, and asks nothing', (tester) async {
      final backend = _backend(_gmail());
      await _pumpScreen(tester, backend,
          user: _user({Privileges.emailView, Privileges.emailSend}, customerId: 5));
      expect(find.text('Gmail is connected by the staff who send email from the app.'),
          findsOneWidget);
      expect(find.byType(TextFormField), findsNothing);
      expect(backend.requests, isEmpty);
    });

    testWidgets('on a phone the page fits', (tester) async {
      await _pumpScreen(tester, _backend(_connected()), size: const Size(400, 1600));
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('How to get these'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text(_stepFive), findsOneWidget);
    });
  });

  group('the account menu', () {
    Future<void> pumpShell(WidgetTester tester, CurrentUser user) async {
      tester.view.physicalSize = const Size(1366, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final router = GoRouter(
        initialLocation: '/',
        routes: [
          ShellRoute(
            builder: (context, state, child) => AppShell(child: child),
            routes: [
              GoRoute(path: '/', builder: (_, __) => const Text('dashboard page')),
              GoRoute(path: '/me/gmail', builder: (_, __) => const Text('gmail page')),
            ],
          ),
        ],
      );
      await tester.pumpWidget(ProviderScope(
        overrides: [
          currentUserProvider.overrideWithValue(user),
          unreadCountProvider.overrideWith((ref) => Stream.value(0)),
        ],
        child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Account'));
      await tester.pumpAndSettle();
    }

    testWidgets('offers staff who send email their Gmail connection, above Change password',
        (tester) async {
      await pumpShell(tester, _staff);

      expect(find.byIcon(Icons.mail_lock_outlined), findsOneWidget);
      expect(tester.getTopLeft(find.text('Gmail connection')).dy,
          lessThan(tester.getTopLeft(find.text('Change password')).dy));
      await tester.tap(find.text('Gmail connection'));
      await tester.pumpAndSettle();
      expect(find.text('gmail page'), findsOneWidget);
    });

    testWidgets('offers it to neither a customer login nor staff who cannot send',
        (tester) async {
      for (final user in [
        _user({Privileges.emailView, Privileges.emailSend}, customerId: 5),
        _user({Privileges.emailView}),
      ]) {
        await pumpShell(tester, user);
        expect(find.text('Change password'), findsOneWidget);
        expect(find.text('Gmail connection'), findsNothing);
      }
    });
  });

  group('a user\'s page', () {
    Map<String, dynamic> account({int? customerId}) => {
          'id': 8,
          'username': 'sam',
          'fullName': 'Sam Sales',
          'email': 'sam@company.com',
          'active': true,
          'role': customerId == null ? 'SALES_POC' : 'CUSTOMER',
          'customerId': customerId,
        };

    Future<FakeBackend> pumpUser(WidgetTester tester,
        {Map<String, dynamic>? gmail, int? customerId}) async {
      tester.view.physicalSize = const Size(1366, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final backend = FakeBackend({
        'GET /api/users/8': (_) => account(customerId: customerId),
        'GET /api/users/8/gmail': (_) => gmail,
      });
      await tester.pumpWidget(ProviderScope(
        key: UniqueKey(),
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(_user({Privileges.userView})),
        ],
        child: MaterialApp(
            theme: AppTheme.light(), home: const Scaffold(body: UserDetailScreen(id: 8))),
      ));
      await tester.pumpAndSettle();
      return backend;
    }

    testWidgets('says whether a member of staff has connected Gmail', (tester) async {
      for (final (json, text) in [
        (
          {
            'status': 'CONNECTED',
            'gmailAddress': 'sam@gmail.com',
            'reason': null,
            'updatedAt': _connectedAt,
          },
          'Connected as sam@gmail.com',
        ),
        (
          {
            'status': 'NEEDS_RECONNECT',
            'gmailAddress': 'sam@gmail.com',
            'reason': 'Reconnect Gmail.',
          },
          'Needs renewing',
        ),
        ({'status': 'NOT_CONNECTED', 'gmailAddress': null, 'reason': null}, 'Not connected'),
      ]) {
        final backend = await pumpUser(tester, gmail: json);
        expect(find.text('Gmail'), findsOneWidget, reason: text);
        expect(find.text(text), findsOneWidget);
        expect(backend.sent('GET /api/users/8/gmail'), hasLength(1));
        expect(find.byTooltip('Reconnect Gmail.'),
            json['status'] == 'NEEDS_RECONNECT' ? findsOneWidget : findsNothing);
      }
    });

    testWidgets('a customer login\'s page has no Gmail line', (tester) async {
      final backend = await pumpUser(tester, customerId: 5);
      expect(find.text('Sam Sales'), findsWidgets);
      expect(find.text('Gmail'), findsNothing);
      expect(backend.sent('GET /api/users/8/gmail'), isEmpty);
    });
  });
}
