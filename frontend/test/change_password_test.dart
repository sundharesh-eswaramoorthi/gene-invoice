import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/storage/secure_storage.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/change_password_dialog.dart';

import 'support/fake_backend.dart';

class _MemoryTokenStorage implements TokenStorage {
  String? token;
  _MemoryTokenStorage(this.token);

  @override
  Future<void> save(String value) async => token = value;
  @override
  Future<String?> read() async => token;
  @override
  Future<void> clear() async => token = null;
}

Future<void> _pumpDialog(
  WidgetTester tester,
  FakeBackend backend,
  _MemoryTokenStorage storage,
) async {
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      tokenStorageProvider.overrideWithValue(storage),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () => showChangePasswordDialog(context),
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

Future<void> _fillAndSubmit(WidgetTester tester) async {
  await tester.enterText(find.widgetWithText(TextFormField, 'Current password'), 'Start123!');
  await tester.enterText(find.widgetWithText(TextFormField, 'New password'), 'Newpass123!');
  await tester.enterText(find.widgetWithText(TextFormField, 'Confirm new password'), 'Newpass123!');
  await tester.tap(find.widgetWithText(FilledButton, 'Change'));
  await tester.pumpAndSettle();
}

void main() {
  group('changing your own password', () {
    // The server ends every session minted against the old password, this one included (AUTH-04),
    // and hands the session that just proved that password a replacement. Storing it is what
    // keeps the user working; without it the dialog says "Password changed successfully" and the
    // very next request 401s them to the sign-in screen.
    testWidgets('stores the token the server hands back, so the session survives', (tester) async {
      final storage = _MemoryTokenStorage('token-of-the-old-password');
      final backend = FakeBackend({
        'POST /api/auth/change-password': (_) => {'status': 'ok', 'token': 'token-of-the-new-one'},
      });

      await _pumpDialog(tester, backend, storage);
      await _fillAndSubmit(tester);

      expect(find.text('Password changed successfully.'), findsOneWidget);
      expect(storage.token, 'token-of-the-new-one');
    });

    testWidgets('keeps the session it has when the answer carries no token', (tester) async {
      final storage = _MemoryTokenStorage('token-of-the-old-password');
      final backend = FakeBackend({
        'POST /api/auth/change-password': (_) => {'status': 'ok'},
      });

      await _pumpDialog(tester, backend, storage);
      await _fillAndSubmit(tester);

      expect(find.text('Password changed successfully.'), findsOneWidget);
      expect(storage.token, 'token-of-the-old-password');
    });

    testWidgets('leaves the token alone when the change is refused', (tester) async {
      final storage = _MemoryTokenStorage('token-of-the-old-password');
      final backend = FakeBackend({
        'POST /api/auth/change-password': (_) =>
            const FakeFailure(400, {'message': 'Current password is incorrect'}),
      });

      await _pumpDialog(tester, backend, storage);
      await _fillAndSubmit(tester);

      expect(find.text('Current password is incorrect'), findsOneWidget);
      expect(storage.token, 'token-of-the-old-password');
    });
  });
}
