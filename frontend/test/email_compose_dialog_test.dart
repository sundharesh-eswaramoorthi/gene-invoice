import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/features/emails/email_compose_dialog.dart';
import 'package:gene_invoice/features/emails/email_models.dart';
import 'package:gene_invoice/features/emails/email_providers.dart';

/// The send-time choices the backend would hand back: two users and two roles, one of the
/// roles deliberately addressless so it would need the application-wide fallback (AC6 hint).
const _options = EmailOptions(
  senders: [
    EmailSenderOption(kind: 'USER', id: 1, label: 'SAM.SALES', email: 'sam@test.local'),
    EmailSenderOption(kind: 'ROLE', id: 2, label: 'BILLING', email: 'billing@test.local'),
    EmailSenderOption(kind: 'ROLE', id: 5, label: 'OPS'),
  ],
  recipients: [
    EmailRecipientOption(kind: 'USER', id: 3, label: 'CARA', email: 'cara@test.local'),
    EmailRecipientOption(kind: 'ROLE', id: 4, label: 'SUPPORT'),
  ],
);

/// Opens the real compose dialog from a button, capturing what it pops with.
Future<void> _open(WidgetTester tester, void Function(Map<String, dynamic>?) onPopped) async {
  tester.view.physicalSize = const Size(1600, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [emailOptionsProvider.overrideWith((ref) async => _options)],
    child: MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () async {
              onPopped(await showEmailComposeDialog(context, contextLabel: 'Acme Ltd'));
            },
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

ButtonStyleButton _sendButton(WidgetTester tester) =>
    tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Send'));

Future<void> _pickFrom(WidgetTester tester, String itemText) async {
  await tester.tap(find.byType(DropdownButtonFormField<EmailSenderOption>));
  await tester.pumpAndSettle();
  await tester.tap(find.text(itemText).last);
  await tester.pumpAndSettle();
}

Future<void> _toggle(WidgetTester tester, String title) async {
  await tester.ensureVisible(find.text(title));
  await tester.tap(find.text(title));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('Send stays disabled without a From or without any recipient (AC5, AC7)',
      (tester) async {
    await _open(tester, (_) {});
    expect(_sendButton(tester).onPressed, isNull);

    // A From alone is not enough — at least one recipient selection is required.
    await _pickFrom(tester, 'SAM.SALES (sam@test.local)');
    expect(_sendButton(tester).onPressed, isNull);

    await _toggle(tester, 'CARA');
    expect(_sendButton(tester).onPressed, isNotNull);
  });

  testWidgets('a named-user draft pops with the exact identifiers and a trimmed subject',
      (tester) async {
    Map<String, dynamic>? result;
    await _open(tester, (r) => result = r);

    await _pickFrom(tester, 'SAM.SALES (sam@test.local)');
    await _toggle(tester, 'CARA');
    await tester.enterText(find.byType(TextFormField).first, '  Quarterly statement  ');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Send'));
    await tester.pumpAndSettle();

    expect(result, {
      'fromUserId': 1,
      'toUserIds': [3],
      'toRoleIds': <int>[],
      'includeCustomerAddress': false,
      'subject': 'Quarterly statement',
      'body': '',
    });
  });

  testWidgets('a role From plus a role To and the Customer address mix through together (AC7)',
      (tester) async {
    Map<String, dynamic>? result;
    await _open(tester, (r) => result = r);

    await _pickFrom(tester, 'Role: BILLING (billing@test.local)');
    await _toggle(tester, 'Role: SUPPORT');
    await _toggle(tester, 'Customer email address');
    await tester.enterText(find.byType(TextFormField).first, 'Invoice pack');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Send'));
    await tester.pumpAndSettle();

    expect(result, {
      'fromRoleId': 2,
      'toUserIds': <int>[],
      'toRoleIds': [4],
      'includeCustomerAddress': true,
      'subject': 'Invoice pack',
      'body': '',
    });
  });

  testWidgets('a spaces-only subject is refused without closing the dialog (AC14)',
      (tester) async {
    var popped = false;
    await _open(tester, (_) => popped = true);

    await _pickFrom(tester, 'SAM.SALES (sam@test.local)');
    await _toggle(tester, 'CARA');
    await tester.enterText(find.byType(TextFormField).first, '   ');
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Send'));
    await tester.pumpAndSettle();

    expect(find.text('Required'), findsOneWidget);   // the Subject validator fires
    expect(popped, isFalse);
    expect(find.byType(AlertDialog), findsOneWidget);   // the draft is still open
  });
}
