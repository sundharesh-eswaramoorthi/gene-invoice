import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/email/email_actions.dart';
import 'package:gene_invoice/features/email/email_models.dart';
import 'package:gene_invoice/features/email/email_providers.dart';
import 'package:gene_invoice/features/email/send_email_dialog.dart' show FromPickerItem;
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

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

const _bob = {'userId': 12, 'name': 'Bob Smith', 'email': 'bob@company.com'};

/// The Sales POC the invoice itself stores — the record's own POC, not the customer's book (L3).
const _divya = {'userId': 15, 'name': 'Divya Nair', 'email': 'divya@company.com'};

Map<String, dynamic> _holder(int userId, String name) =>
    {'userId': userId, 'name': name, 'email': '${name.split(' ').first.toLowerCase()}@company.com'};

/// An invoice's context as §4 of email-role-levels.md has it: the customer's three POC seats, then
/// the invoice's own Sales POC. [salesPocs], [successPocs] and [collectionPocs] are the active
/// holders of those seats on the customer, primary first; [recordSalesPoc] is the one person the
/// invoice stores (null: nobody holds it there). [configured]: a mail service is set up;
/// [selfGmail]: the caller's own Gmail connection.
Map<String, dynamic> _context({
  bool restricted = false,
  bool withRecord = true,
  Map<String, dynamic>? suggestion,
  List<Map<String, dynamic>> salesPocs = const [],
  List<Map<String, dynamic>> successPocs = const [],
  List<Map<String, dynamic>> collectionPocs = const [_bob],
  Map<String, dynamic>? recordSalesPoc = _divya,
  bool configured = false,
  String? selfGmail,
}) {
  Map<String, dynamic> role(
      String key, String label, String levelLabel, List<Map<String, dynamic>> holders) {
    // A customer login sees that a role is held, never by whom (E13); before a record is chosen
    // there is nobody to resolve yet.
    final seen = restricted || !withRecord ? const <Map<String, dynamic>>[] : holders;
    return {
      'role': key,
      'label': label,
      'level': levelLabel == 'Customer' ? 'CUSTOMER' : 'RECORD',
      'levelLabel': levelLabel,
      'groupLabel': '$levelLabel level',
      'resolved': withRecord ? holders.isNotEmpty : null,
      'people': seen,
      'sender': seen.isEmpty ? null : seen.first,
    };
  }

  return {
    'entityType': 'INVOICE',
    'entityId': withRecord ? 42 : null,
    'entityLabel': withRecord ? 'Invoice INV-0042' : null,
    'entityLink': withRecord ? '/invoices/42' : null,
    'delivery': {'configured': configured},
    'sender': {
      'restricted': restricted,
      'self': {
        'userId': 3,
        'name': restricted ? 'Acme Buyer' : 'Jane Doe',
        'email': 'me@x.com',
        if (selfGmail != null) 'gmail': selfGmail,
      },
    },
    'roles': [
      role('SALES_POC', 'Sales POC', 'Customer', salesPocs),
      role('CUSTOMER_SUCCESS_POC', 'Customer Success POC', 'Customer', successPocs),
      role('COLLECTION_POC', 'Collection POC', 'Customer', collectionPocs),
      role('SALES_POC', 'Sales POC', 'Invoice', [if (recordSalesPoc != null) recordSalesPoc]),
    ],
    'customerEmails': {
      'available': true,
      'addresses': withRecord
          ? [
              {'name': 'Acme Ltd', 'address': 'ap@acme.com'},
            ]
          : [],
    },
    'suggestion': suggestion,
  };
}

Map<String, dynamic> _savedEmail(String status, {String? error}) => {
      'id': 91,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'direction': 'OUTBOUND',
      'status': status,
      'subject': 'Payment reminder',
      'body': '',
      'from': {'name': 'Jane Doe', 'address': 'me@x.com', 'internal': true, 'masked': false},
      'to': [],
      'cc': [],
      'unresolved': [],
      'error': error,
      'attempts': 1,
      'occurredAt': '2026-09-17T10:15:00Z',
      'canRetry': false,
    };

/// A fake backend: each "METHOD /path" answers with what its handler returns, and every request
/// is kept so a test can read what the dialog sent. A request in [held] is answered only once its
/// future completes.
class _Backend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  final Map<String, Future<void>> held = {};
  _Backend(this.routes);

  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
      requests.add(options);
      final gate = held['${options.method} ${options.path}'];
      if (gate != null) await gate;
      final route = routes['${options.method} ${options.path}'];
      if (route == null) {
        handler.reject(DioException(
          requestOptions: options,
          response: Response(
              requestOptions: options, statusCode: 404, data: {'message': 'no fake route'}),
        ));
        return;
      }
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: route(options)));
    }));
}

_Backend _backend({
  Map<String, dynamic>? context,
  List<String> problems = const [],
  List<String> warnings = const [],
  String status = 'SENT',
}) =>
    _Backend({
      'GET /api/emails/context': (_) => context ?? _context(),
      'POST /api/emails/preview': (_) => {
            'from': {'name': 'Jane Doe', 'address': 'me@x.com', 'internal': true, 'masked': false},
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
            'unresolved': [],
            'problems': problems,
            'warnings': warnings,
          },
      'GET /api/emails/people': (_) => [
            {'userId': 7, 'name': 'Ann Admin', 'username': 'ann', 'email': 'ann@company.com'},
          ],
      'POST /api/emails': (_) => _savedEmail(status, error: status == 'SENT' ? null : 'No Gmail'),
    });

Future<void> _pump(
  WidgetTester tester, {
  required _Backend backend,
  CurrentUser user = _staff,
  Size size = const Size(1366, 900),
  required Future<void> Function(BuildContext context) onOpen,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

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
            child: TextButton(onPressed: () => onOpen(context), child: const Text('open')),
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

FilledButton _sendButton(WidgetTester tester, String label) => tester.widget<FilledButton>(
    find.ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is FilledButton)));

/// Every tooltip the form has on show, but the To chips' "Remove". A chip that cannot be pressed
/// builds no tooltip of its own, so a chip's `tooltip` field alone would not prove one is there.
List<String> _tooltips(WidgetTester tester) => [
      for (final t in tester.widgetList<Tooltip>(find.byType(Tooltip)))
        if ((t.message ?? '').isNotEmpty && t.message != 'Remove') t.message!,
    ];

/// What the From field's menu says for [value], without opening it.
String? _fromItemText(WidgetTester tester, String value) {
  final items = tester.widget<DropdownButton<String>>(find.byType(DropdownButton<String>)).items!;
  return _itemWords(items.singleWhere((i) => i.value == value).child);
}

/// The words of a From item, however it lays them out: the name, which gives way when the row is
/// narrow, and the Gmail marker beside it, which does not.
String _itemWords(Widget widget) => switch (widget) {
      FromPickerItem(:final name, :final note) => '$name$note',
      DropdownMenuItem(:final child) => _itemWords(child),
      Text(:final data) => data ?? '',
      Flexible(:final child) => _itemWords(child),
      Row(:final children) => children.map(_itemWords).join(),
      _ => '',
    };

/// What the From field itself reads with the menu closed — the chosen item, away from the
/// headings that name its level in the menu.
String _closedFrom(WidgetTester tester) {
  final stack = tester.widget<IndexedStack>(find.descendant(
      of: find.byType(DropdownButton<String>), matching: find.byType(IndexedStack)));
  return _itemWords(stack.children[stack.index!]);
}

/// Opens the From menu from what the closed field reads now and picks one of its lines; [at] tells
/// two alike apart, as the two levels of one role are in bulk.
Future<void> _chooseFrom(WidgetTester tester, String open, String line, {int at = 0}) async {
  await tester.tap(find.text(open));
  await tester.pumpAndSettle();
  await tester.tap(
      find.descendant(of: find.byType(ListView).last, matching: find.text(line)).at(at));
  await tester.pumpAndSettle();
}

/// Every line of the From field's menu in order, the group headings among the senders.
List<String> _fromMenu(WidgetTester tester) => [
      for (final item
          in tester.widget<DropdownButton<String>>(find.byType(DropdownButton<String>)).items!)
        _itemWords(item.child),
    ];

/// The chips offered under one heading of To — "Add", "Customer level", "Invoice level" — in order.
List<String> _chipsUnder(WidgetTester tester, String heading) {
  return [
    for (final chip in tester.widgetList<ActionChip>(
        find.descendant(of: _row(heading), matching: find.byType(ActionChip))))
      (chip.label as Text).data ?? '',
  ];
}

/// One chip by the heading it sits under, for the words both levels offer ("Sales POC · nobody
/// assigned").
Finder _chipIn(String heading, String text) =>
    find.descendant(of: _row(heading), matching: find.widgetWithText(ActionChip, text));

Finder _row(String heading) =>
    find.ancestor(of: find.text(heading), matching: find.byType(Wrap)).first;

Future<void> _openInvoice(BuildContext context,
    {void Function(EmailComposeOutcome)? onResult}) async {
  final sent = await showSendEmailDialog(context,
      type: EmailEntityType.invoice, entityId: 42, entityLabel: 'Invoice INV-0042');
  onResult?.call(sent);
}

void main() {
  group('a role from the server', () {
    test('lists everyone it reaches, primary first, and the one person who sends', () {
      final anil = _holder(21, 'Anil Kumar');
      final bala = _holder(22, 'Bala Raman');
      final option = EmailRoleOption.fromJson({
        'role': 'COLLECTION_POC',
        'label': 'Collection POC',
        'resolved': true,
        'people': [anil, bala],
        'sender': anil,
      });
      expect(option.resolved, isTrue);
      expect([for (final p in option.people) p.display],
          ['Anil Kumar <anil@company.com>', 'Bala Raman <bala@company.com>']);
      expect([for (final p in option.people) p.userId], [21, 22]);
      expect(option.sender?.display, 'Anil Kumar <anil@company.com>');

      // The same read as part of the whole context.
      final ctx = EmailContext.fromJson(_context(collectionPocs: [anil, bala]));
      expect([for (final p in ctx.role('COLLECTION_POC')!.people) p.userId], [21, 22]);
      expect(ctx.role('COLLECTION_POC')!.sender?.userId, 21);
      expect(ctx.role('SALES_POC')!.people, isEmpty);
    });

    test('reaches nobody when people and sender are left out, empty or null', () {
      const base = {'role': 'SALES_POC', 'label': 'Sales POC', 'resolved': false};
      for (final json in <Map<String, dynamic>>[
        base,
        {...base, 'people': [], 'sender': null},
        {...base, 'people': null},
      ]) {
        final option = EmailRoleOption.fromJson(json);
        expect(option.people, isEmpty, reason: '$json');
        expect(option.sender, isNull, reason: '$json');
      }
    });

    test('says which level it is, and the group it belongs to', () {
      final ctx = EmailContext.fromJson(_context());
      expect([for (final r in ctx.roles) '${r.role} ${r.level}'], [
        'SALES_POC CUSTOMER',
        'CUSTOMER_SUCCESS_POC CUSTOMER',
        'COLLECTION_POC CUSTOMER',
        'SALES_POC RECORD',
      ]);
      // The customer's POC book first, then what the invoice itself stores (§4).
      expect([for (final g in ctx.roleGroups) g.label], ['Customer level', 'Invoice level']);
      expect([for (final g in ctx.roleGroups) g.roles.length], [3, 1]);
      expect(ctx.roleGroups.last.roles.single.levelLabel, 'Invoice');
      // The same role at the two levels is two options, told apart by their level.
      expect(ctx.role('SALES_POC', level: EmailRoleLevel.record)!.sender?.name, 'Divya Nair');
      expect(ctx.role('SALES_POC', level: EmailRoleLevel.customer)!.people, isEmpty);
      expect(ctx.role('COLLECTION_POC', level: EmailRoleLevel.record), isNull);
      expect(ctx.roleOf(const EmailToken.role('SALES_POC', level: 'RECORD'))!.sender?.userId, 15);
      // A token with no level is the customer's, where the type offers one, as the server reads
      // it (L7).
      expect(ctx.roleOf(const EmailToken.role('SALES_POC'))!.level, EmailRoleLevel.customer);
      expect(ctx.roleOf(const EmailToken.customer()), isNull);
    });

    test('sent without a level, it is the customer\'s POC book (L7)', () {
      final option = EmailRoleOption.fromJson(
          const {'role': 'SALES_POC', 'label': 'Sales POC', 'resolved': false});
      expect(option.level, EmailRoleLevel.customer);
      expect(option.levelLabel, 'Customer');
      expect(option.groupLabel, 'Customer level');
      expect(option.token, const EmailToken.role('SALES_POC', level: EmailRoleLevel.customer));
      // Every such role is then one group, as the form has always shown them.
      final ctx = EmailContext.fromJson({
        ..._context(),
        'roles': const [
          {'role': 'SALES_POC', 'label': 'Sales POC'},
          {'role': 'COLLECTION_POC', 'label': 'Collection POC'},
        ],
      });
      expect([for (final g in ctx.roleGroups) g.label], ['Customer level']);
      expect(ctx.roleGroups.single.roles, hasLength(2));
    });
  });

  test('a role token carries its level to the server', () {
    const customer = EmailToken.role('SALES_POC', level: EmailRoleLevel.customer);
    const record = EmailToken.role('SALES_POC', level: EmailRoleLevel.record);
    expect(customer.toJson(), {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'CUSTOMER'});
    expect(record.toJson(), {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'});
    // The same role at the two levels is two entries, never one (L1).
    expect(customer == record, isFalse);
    expect(<EmailToken>{customer, record}..add(customer), hasLength(2));
    expect(
        EmailToken.fromJson(const {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'}), record);
    // A token written before levels says nothing about them; the server reads it as customer
    // level where the type offers one (L7).
    expect(const EmailToken.role('SALES_POC').toJson(), {'type': 'ROLE', 'role': 'SALES_POC'});
    expect(EmailToken.fromJson(const {'type': 'USER', 'userId': 7}).level, isNull);
  });

  testWidgets('a role in To names everyone who holds it; as From, the one person who sends',
      (tester) async {
    final anil = _holder(21, 'Anil Kumar');
    final bala = _holder(22, 'Bala Raman');
    final chitra = _holder(23, 'Chitra Devi');
    const anilInFull = 'Anil Kumar <anil@company.com>';
    for (final (holders, text, tooltip) in <(List<Map<String, dynamic>>, String, String?)>[
      ([], 'Collection POC · nobody assigned', null),
      ([anil], 'Collection POC · $anilInFull', null),
      ([anil, bala], 'Collection POC · Anil Kumar, Bala Raman', '$anilInFull\nBala Raman <bala@company.com>'),
      (
        [anil, bala, chitra],
        'Collection POC · Anil Kumar + 2 more',
        '$anilInFull\nBala Raman <bala@company.com>\nChitra Devi <chitra@company.com>',
      ),
    ]) {
      await _pump(tester,
          backend: _backend(context: _context(collectionPocs: holders)), onOpen: _openInvoice);

      final offered = find.widgetWithText(ActionChip, text);
      expect(offered, findsOneWidget, reason: text);
      // Names alone leave out the addresses, which the tooltip gives, one person a line.
      expect(_tooltips(tester), [if (tooltip != null) tooltip], reason: text);
      // An email has one sender, whoever else holds the role: the primary.
      expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'),
          holders.isEmpty ? 'Collection POC · nobody assigned' : 'Collection POC · $anilInFull',
          reason: text);

      // Once added, its chip in To says the same.
      await tester.tap(offered);
      await tester.pumpAndSettle();
      expect(find.widgetWithText(InputChip, text), findsOneWidget, reason: text);
      expect(_tooltips(tester), [if (tooltip != null) tooltip], reason: text);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
    }
  });

  testWidgets('To offers the roles in labelled groups: the customer\'s book, then the invoice\'s own',
      (tester) async {
    await _pump(tester,
        backend: _backend(context: _context(successPocs: [_holder(13, 'Chitra Devi')])),
        onOpen: _openInvoice);

    // The customer's three seats first, in their own order, then what the invoice stores (§4).
    expect(_chipsUnder(tester, 'Customer level'), [
      'Sales POC · nobody assigned',
      'Customer Success POC · Chitra Devi <chitra@company.com>',
      'Collection POC · Bob Smith <bob@company.com>',
    ]);
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC · Divya Nair <divya@company.com>']);
    expect(_chipsUnder(tester, 'Add'), ['Person…']);
    double top(String text) => tester.getTopLeft(find.text(text)).dy;
    expect(top('Customer level'), lessThan(top('Invoice level')));
    // The customer's own addresses come after both groups.
    expect(top('Invoice level'), lessThan(top('Customer emails · ap@acme.com')));

    // Either Sales POC can be picked, or both: each chip reaches the people of its own level.
    await tester.tap(_chipIn('Invoice level', 'Sales POC · Divya Nair <divya@company.com>'));
    await tester.pumpAndSettle();
    await tester.tap(_chipIn('Customer level', 'Sales POC · nobody assigned'));
    await tester.pumpAndSettle();
    // In the box, away from the headings, each of the two says which POC it is — in the server's
    // own words for a source.
    expect(
        find.widgetWithText(
            InputChip, 'Sales POC (this invoice) · Divya Nair <divya@company.com>'),
        findsOneWidget);
    expect(find.widgetWithText(InputChip, 'Sales POC (customer) · nobody assigned'), findsOneWidget);
    // A group with nothing left to add is gone, heading and all.
    expect(find.text('Invoice level'), findsNothing);
    expect(_chipsUnder(tester, 'Customer level'), [
      'Customer Success POC · Chitra Devi <chitra@company.com>',
      'Collection POC · Bob Smith <bob@company.com>',
    ]);
  });

  testWidgets('a level the record has no roles at is left out of To and of From', (tester) async {
    // A customer record keeps no POC of its own, so only the customer's book is offered (L3).
    final context = _context();
    context['roles'] = [
      for (final role in context['roles'] as List)
        if ((role as Map)['level'] == 'CUSTOMER') role,
    ];
    context['entityType'] = 'CUSTOMER';
    await _pump(tester, backend: _backend(context: context), onOpen: _openInvoice);

    expect(find.text('Customer level'), findsOneWidget);
    expect(find.text('Invoice level'), findsNothing);
    expect(find.textContaining(' level'), findsOneWidget);
    expect(_chipsUnder(tester, 'Customer level'), hasLength(3));
    expect(_fromMenu(tester), [
      'Me (Jane Doe)',
      'Customer level',
      'Sales POC · nobody assigned',
      'Customer Success POC · nobody assigned',
      'Collection POC · Bob Smith <bob@company.com>',
      'Someone else…',
    ]);
  });

  testWidgets('From offers the same groups, each role naming the one person who would send',
      (tester) async {
    final backend = _backend(
        context: _context(salesPocs: [_holder(21, 'Anil Kumar'), _holder(22, 'Bala Raman')]));
    await _pump(tester, backend: backend, onOpen: _openInvoice);

    expect(_fromMenu(tester), [
      'Me (Jane Doe)',
      'Customer level',
      // The seat reaches both holders in To; only its primary can be the sender (L5).
      'Sales POC · Anil Kumar <anil@company.com>',
      'Customer Success POC · nobody assigned',
      'Collection POC · Bob Smith <bob@company.com>',
      'Invoice level',
      'Sales POC · Divya Nair <divya@company.com>',
      'Someone else…',
    ]);
    final items =
        tester.widget<DropdownButton<String>>(find.byType(DropdownButton<String>)).items!;
    // A heading names its group and is never a sender.
    expect([for (final i in items) i.value!].where((v) => v.startsWith('group:')),
        ['group:CUSTOMER', 'group:RECORD']);
    expect([for (final i in items) if (!i.enabled) i.value], ['group:CUSTOMER', 'group:RECORD']);

    // Chosen, the invoice's own Sales POC goes to the server with its level.
    await tester.tap(find.text('Me (Jane Doe)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sales POC · Divya Nair <divya@company.com>').last);
    await tester.pumpAndSettle();
    expect(_fromItemText(tester, 'role:SALES_POC:RECORD'),
        'Sales POC · Divya Nair <divya@company.com>');
    await tester.tap(find.text('Customer emails · ap@acme.com'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Statement');
    await _settlePreview(tester);
    expect(backend.sent('POST /api/emails/preview').last.data['from'],
        {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'});
  });

  testWidgets('the closed From field says which of the two levels the chosen role is',
      (tester) async {
    final backend = _backend(
        context: _context(salesPocs: [_holder(21, 'Anil Kumar'), _holder(22, 'Bala Raman')]));
    await _pump(tester, backend: backend, onOpen: _openInvoice);

    // The menu says the level once, in the heading over each group; the closed field has no
    // heading over it, so there the role names its own level (§4).
    expect(_closedFrom(tester), 'Me (Jane Doe)');
    await _chooseFrom(tester, 'Me (Jane Doe)', 'Sales POC · Divya Nair <divya@company.com>');
    expect(_closedFrom(tester), 'Sales POC (this invoice) · Divya Nair <divya@company.com>');
    await _chooseFrom(tester, 'Sales POC (this invoice) · Divya Nair <divya@company.com>',
        'Sales POC · Anil Kumar <anil@company.com>');
    expect(_closedFrom(tester), 'Sales POC (customer) · Anil Kumar <anil@company.com>');
    // Offered at one level only, it reads as it does in the menu.
    await _chooseFrom(tester, 'Sales POC (customer) · Anil Kumar <anil@company.com>',
        'Collection POC · Bob Smith <bob@company.com>');
    expect(_closedFrom(tester), 'Collection POC · Bob Smith <bob@company.com>');
  });

  testWidgets('a role the record itself has nobody in is offered all the same', (tester) async {
    // Both levels are always offered where they exist, held or not (L4).
    final backend = _backend(context: _context(recordSalesPoc: null));
    backend.routes['POST /api/emails/preview'] = (_) => {
          'to': [],
          'unresolved': [
            {
              'token': 'ROLE:RECORD:SALES_POC',
              'label': 'Sales POC (this invoice)',
              'reason': 'Invoice INV-0042 has no Sales POC',
            },
          ],
          'problems': ['No recipients: Sales POC is not assigned'],
        };
    await _pump(tester, backend: backend, onOpen: _openInvoice);

    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC · nobody assigned']);
    expect(_fromItemText(tester, 'role:SALES_POC:RECORD'), 'Sales POC · nobody assigned');

    await tester.tap(_chipIn('Invoice level', 'Sales POC · nobody assigned'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Statement');
    await _settlePreview(tester);
    expect(backend.sent('POST /api/emails/preview').last.data['to'],
        [{'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'}]);
    // The server's own words for the level say which Sales POC found nobody.
    expect(find.text('Sales POC (this invoice) — nobody assigned'), findsOneWidget);
    expect(_sendButton(tester, 'Send').onPressed, isNull);
    // The customer's seat is still there to add beside it.
    expect(_chipsUnder(tester, 'Customer level').first, 'Sales POC · nobody assigned');
  });

  testWidgets('five holders fit their chip and the preview lists each, on a phone and a laptop',
      (tester) async {
    final holders = [
      _holder(21, 'Anil Kumar Venkataraman Subramaniam'),
      _holder(22, 'Bala Raman Krishnamurthy'),
      _holder(23, 'Chitra Devi Balasubramanian'),
      _holder(24, 'Divya Lakshmi Narayanan'),
      _holder(25, 'Esha Parthasarathy Iyengar'),
    ];
    String inFull(Map<String, dynamic> h) => '${h['name']} <${h['email']}>';
    const chip = 'Collection POC · Anil Kumar Venkataraman Subramaniam + 4 more';

    for (final size in const [Size(400, 820), Size(1366, 900)]) {
      final backend = _backend(context: _context(collectionPocs: holders));
      backend.routes['POST /api/emails/preview'] = (_) => {
            'from': {
              'name': holders.first['name'],
              'address': holders.first['email'],
              'internal': true,
              'masked': false,
            },
            'to': [
              for (final h in holders)
                {
                  'name': h['name'],
                  'address': h['email'],
                  'userId': h['userId'],
                  'internal': true,
                  'masked': false,
                  'sources': [
                    {'type': 'ROLE', 'role': 'COLLECTION_POC', 'label': 'Collection POC'},
                  ],
                },
            ],
            'unresolved': [],
            'problems': [],
          };
      await _pump(tester, backend: backend, size: size, onOpen: _openInvoice);

      await tester.tap(find.text('Me (Jane Doe)'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Collection POC · ${inFull(holders.first)}').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(ActionChip, chip));
      await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Statement');
      await _settlePreview(tester);

      expect(tester.takeException(), isNull, reason: '$size');
      expect(find.widgetWithText(InputChip, chip), findsOneWidget);
      // A Wrap reports no overflow for a child wider than itself, so check where they end: the
      // chip's label and the sender's name are each wider than a phone.
      for (final field in [find.byType(InputChip), find.byType(DropdownButton<String>)]) {
        expect(tester.getRect(field).right, lessThanOrEqualTo(size.width), reason: '$size');
      }
      expect(find.byTooltip(holders.map(inFull).join('\n')), findsOneWidget);
      for (final h in holders) {
        expect(find.text('${inFull(h)} — Collection POC'), findsOneWidget, reason: '$size');
      }
      expect(_sendButton(tester, 'Send').onPressed, isNotNull);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
    }
  });

  testWidgets('the subject is required before anything is sent', (tester) async {
    final backend = _backend();
    await _pump(tester, backend: backend, onOpen: _openInvoice);

    expect(find.text('Send email'), findsOneWidget);
    expect(find.text('About: Invoice INV-0042'), findsOneWidget);
    expect(
      find.text('Email delivery is not configured. The email will be saved in the app but not '
          'sent.'),
      findsOneWidget,
    );

    await tester.tap(find.text('Customer emails · ap@acme.com'));
    await _settlePreview(tester);
    expect(find.text('Acme Ltd <ap@acme.com> — customer email'), findsOneWidget);

    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Enter a subject'), findsOneWidget);
    expect(backend.sent('POST /api/emails'), isEmpty);
  });

  testWidgets('"Enter a subject" goes as soon as a subject is typed, as the To error does',
      (tester) async {
    final backend = _backend();
    await _pump(tester, backend: backend, onOpen: _openInvoice);
    final subject = find.widgetWithText(TextFormField, 'Subject *');

    // Before any Send, emptying the field says nothing yet.
    await tester.enterText(subject, 'x');
    await tester.enterText(subject, '');
    await tester.pumpAndSettle();
    expect(find.text('Enter a subject'), findsNothing);

    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Add at least one recipient'), findsOneWidget);
    expect(find.text('Enter a subject'), findsOneWidget);

    await tester.tap(find.text('Customer emails · ap@acme.com'));
    await tester.enterText(subject, 'Payment reminder');
    await _settlePreview(tester);
    expect(find.text('Add at least one recipient'), findsNothing);
    expect(find.text('Enter a subject'), findsNothing);

    // Emptied again after that first Send, it says so at once.
    await tester.enterText(subject, '   ');
    await tester.pumpAndSettle();
    expect(find.text('Enter a subject'), findsOneWidget);
    expect(backend.sent('POST /api/emails'), isEmpty);
  });

  testWidgets('the form tells the server the local UTC offset, for the dates it writes',
      (tester) async {
    final offset = DateTime.now().timeZoneOffset.inMinutes;
    final backend = _backend();
    await _pump(tester, backend: backend, onOpen: _openInvoice);
    expect(backend.sent('GET /api/emails/context').single.queryParameters,
        {'entityType': 'INVOICE', 'entityId': 42, 'utcOffsetMinutes': offset});
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    // A suggestion after a save, and a list page's form before a record is chosen, ask the same.
    final notify = _backend(context: _context(suggestion: {'subject': 's', 'body': '', 'to': []}));
    await _pump(tester,
        backend: notify,
        onOpen: (context) => notifyByEmailAfterSave(context,
            notify: true, type: EmailEntityType.invoice, entityId: 42, event: EmailEvent.created));
    expect(notify.sent('GET /api/emails/context').single.queryParameters,
        {'entityType': 'INVOICE', 'entityId': 42, 'event': 'CREATED', 'utcOffsetMinutes': offset});
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    final picker = _backend(context: _context(withRecord: false));
    await _pump(tester,
        backend: picker,
        onOpen: (context) => showSendEmailForPickedRecord(context, type: EmailEntityType.invoice));
    expect(picker.sent('GET /api/emails/context').single.queryParameters,
        {'entityType': 'INVOICE', 'utcOffsetMinutes': offset});
  });

  testWidgets('Send stays disabled while the preview lists a problem', (tester) async {
    const problem = 'No recipients: Sales POC is not assigned and the customer has no email address';
    final backend = _backend(problems: [problem]);
    await _pump(tester, backend: backend, onOpen: _openInvoice);

    // An unassigned role says so on its chip, before any preview.
    await tester.tap(find.text('Sales POC · nobody assigned'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Reminder');
    await _settlePreview(tester);

    expect(find.text(problem), findsOneWidget);
    expect(_sendButton(tester, 'Send').onPressed, isNull);

    // Removing the only recipient clears the problem, and Send says what is missing instead.
    await tester.tap(find.byTooltip('Remove'));
    await _settlePreview(tester);
    expect(find.text(problem), findsNothing);
    expect(_sendButton(tester, 'Send').onPressed, isNotNull);
    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Add at least one recipient'), findsOneWidget);
    expect(backend.sent('POST /api/emails'), isEmpty);
  });

  testWidgets('the request carries exactly the chosen sender and recipients', (tester) async {
    final backend = _backend(status: 'NOT_SENT');
    EmailComposeOutcome? result;
    await _pump(tester,
        backend: backend, onOpen: (c) => _openInvoice(c, onResult: (r) => result = r));

    await tester.tap(find.text('Me (Jane Doe)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Collection POC · Bob Smith <bob@company.com>').last);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Customer emails · ap@acme.com'));
    await tester.tap(find.text('Person…'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Ann Admin'));
    await tester.pumpAndSettle();
    // The same person can be the sender (in From) and a recipient (the role's chip).
    await tester.tap(find.widgetWithText(ActionChip, 'Collection POC · Bob Smith <bob@company.com>'));
    await tester.pumpAndSettle();
    // And the invoice's own Sales POC beside the customer's seats.
    await tester.tap(_chipIn('Invoice level', 'Sales POC · Divya Nair <divya@company.com>'));
    await tester.pumpAndSettle();

    expect(find.text('Ann Admin <ann@company.com>'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), '  Payment reminder ');
    await tester.enterText(find.widgetWithText(TextFormField, 'Message'), 'Please pay.');
    await _settlePreview(tester);

    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();

    final posted = backend.sent('POST /api/emails');
    expect(posted, hasLength(1));
    // Every role token says which level it is, so the server knows which POC is meant (L1).
    expect(posted.single.data, {
      'entityType': 'INVOICE',
      'from': {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
      'to': [
        {'type': 'CUSTOMER'},
        {'type': 'USER', 'userId': 7},
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
      ],
      'subject': 'Payment reminder',
      'body': 'Please pay.',
      'entityId': 42,
    });
    // The preview asked about the same form.
    expect(backend.sent('POST /api/emails/preview').last.data['from'],
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'});
    expect(find.text('Email saved — not sent: No Gmail'), findsOneWidget);
    expect(find.text('Send email'), findsNothing);
    expect(result, EmailComposeOutcome.sent);
  });

  testWidgets('closing the form while its email is out still says how it went, and refreshes',
      (tester) async {
    // Closed and gone before the answer, or still animating out when it comes.
    for (final goneFirst in [true, false]) {
      final answer = Completer<void>();
      final backend = _backend()..held['POST /api/emails'] = answer.future;
      EmailComposeOutcome? result;
      await _pump(tester,
          backend: backend, onOpen: (c) => _openInvoice(c, onResult: (r) => result = r));
      // The sidebar's Inbox badge, which a send refreshes.
      ProviderScope.containerOf(tester.element(find.text('open')))
          .listen(inboxUnreadCountProvider, (_, __) {});
      int badgeFetches() => backend.sent('GET /api/inbox/unread-count').length;

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Reminder');
      await _settlePreview(tester);
      final before = badgeFetches();
      await tester.tap(find.text('Send'));
      await tester.pump();

      // The back button still closes the dialog while it sends.
      await Navigator.of(tester.element(find.text('About: Invoice INV-0042'))).maybePop();
      if (goneFirst) {
        await tester.pumpAndSettle();
        expect(find.text('About: Invoice INV-0042'), findsNothing);
      } else {
        await tester.pump();
      }

      answer.complete();
      await tester.pumpAndSettle();
      await tester.pump(Duration.zero);
      expect(tester.takeException(), isNull);
      expect(find.text('Email sent'), findsOneWidget, reason: 'goneFirst: $goneFirst');
      expect(find.text('open'), findsOneWidget, reason: 'the page behind stays');
      expect(badgeFetches(), before + 1);
      // Closed before the answer, by the back button: the form never said it had sent.
      expect(result, EmailComposeOutcome.closed);
    }
  });

  testWidgets('a customer login writes as themselves, to roles and customer emails only',
      (tester) async {
    final backend = _backend(
      context: _context(
        restricted: true,
        salesPocs: [_holder(13, 'Bala Raman')],
        successPocs: [_holder(14, 'Chitra Devi')],
        collectionPocs: [_bob, _holder(13, 'Bala Raman'), _holder(14, 'Chitra Devi')],
      ),
    );
    await _pump(tester,
        backend: backend,
        user: _customerLogin,
        size: const Size(400, 820),
        onOpen: _openInvoice);

    expect(tester.takeException(), isNull);
    expect(find.text('You'), findsOneWidget);
    expect(find.text('Me (Acme Buyer)'), findsNothing);
    expect(find.text('Someone else…'), findsNothing);
    expect(find.text('Person…'), findsNothing);
    // Held, by several, but by nobody this viewer may see: the role's label and nothing more,
    // under the same headings as anyone else's (E13).
    final offered = find.widgetWithText(ActionChip, 'Collection POC');
    expect(offered, findsOneWidget);
    expect(_chipsUnder(tester, 'Customer level'),
        ['Sales POC', 'Customer Success POC', 'Collection POC']);
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC']);
    expect(find.textContaining('Bob Smith'), findsNothing);
    expect(find.textContaining('company.com'), findsNothing);
    expect(find.textContaining('nobody assigned'), findsNothing);
    expect(_tooltips(tester), isEmpty);

    await tester.tap(offered);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.widgetWithText(InputChip, 'Collection POC'), findsOneWidget);
    expect(_tooltips(tester), isEmpty);
    expect(backend.sent('GET /api/emails/people'), isEmpty);
  });

  testWidgets('on a phone the whole form fits without overflowing', (tester) async {
    final backend = _backend();
    await _pump(tester, backend: backend, size: const Size(400, 820), onOpen: _openInvoice);

    // Both groups and the customer's addresses are below the fold on a phone; the form scrolls
    // to them rather than cutting them off.
    for (final chip in [
      find.text('Customer emails · ap@acme.com'),
      find.text('Collection POC · Bob Smith <bob@company.com>'),
      _chipIn('Invoice level', 'Sales POC · Divya Nair <divya@company.com>'),
    ]) {
      await tester.ensureVisible(chip);
      await tester.pumpAndSettle();
      await tester.tap(chip);
      await tester.pumpAndSettle();
    }
    await _settlePreview(tester);
    expect(tester.takeException(), isNull);
    expect(find.byType(InputChip), findsNWidgets(3));
    expect(find.text('Send'), findsOneWidget);
  });

  testWidgets('notify after save opens the form filled with the suggestion', (tester) async {
    final backend = _backend(
      context: _context(suggestion: {
        'subject': 'Invoice INV-0042 for ₹1,200.00',
        'body': 'Total ₹1,200.00',
        'to': [
          {'type': 'CUSTOMER'},
          {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
          // Suggested before levels: the customer's book, where the type offers one (L7).
          {'type': 'ROLE', 'role': 'COLLECTION_POC'},
        ],
      }),
    );
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) => notifyByEmailAfterSave(context,
          notify: true, type: EmailEntityType.invoice, entityId: 42, event: EmailEvent.created),
    );

    expect(backend.sent('GET /api/emails/context').single.queryParameters['event'], 'CREATED');
    expect(find.text('Invoice INV-0042 for ₹1,200.00'), findsOneWidget);
    expect(find.text('Total ₹1,200.00'), findsOneWidget);
    await _settlePreview(tester);
    expect(find.byTooltip('Remove'), findsNWidgets(3));
    // Each suggested role went in at the level this invoice offers it, so its chip is the one its
    // group offers and neither is offered twice.
    expect(find.widgetWithText(InputChip, 'Sales POC · Divya Nair <divya@company.com>'),
        findsOneWidget);
    expect(_chipsUnder(tester, 'Customer level'),
        ['Sales POC · nobody assigned', 'Customer Success POC · nobody assigned']);
    expect(find.text('Invoice level'), findsNothing);
    expect(backend.sent('POST /api/emails/preview'), hasLength(1));
    expect(backend.sent('POST /api/emails/preview').single.data['to'], [
      {'type': 'CUSTOMER'},
      {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
      {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
    ]);
  });

  testWidgets('without the checkbox ticked nothing opens', (tester) async {
    final backend = _backend();
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) => notifyByEmailAfterSave(context,
          notify: false, type: EmailEntityType.invoice, entityId: 42, event: EmailEvent.created),
    );
    expect(find.text('Send email'), findsNothing);
    expect(backend.requests, isEmpty);
  });

  testWidgets('from a list page the form first asks which record the email is about',
      (tester) async {
    final backend = _backend();
    backend.routes['GET /api/emails/context'] =
        (o) => _context(withRecord: o.queryParameters['entityId'] == 42);
    backend.routes['GET /api/invoices'] = (o) => {
            'content': [
              {'id': 42, 'invoiceNumber': 'INV-0042', 'customerName': 'Acme Ltd', 'total': 1200},
            ],
            'page': 0,
            'size': 20,
            'totalElements': 1,
            'totalPages': 1,
          };
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) => showSendEmailForPickedRecord(context, type: EmailEntityType.invoice),
    );

    expect(find.text('Choose the invoice this email is about'), findsOneWidget);
    // Both groups are offered before a record is chosen, the roles named alone until there is one
    // to resolve them against (§4).
    expect(_chipsUnder(tester, 'Customer level'),
        ['Sales POC', 'Customer Success POC', 'Collection POC']);
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC']);
    expect(_fromMenu(tester), [
      'Me (Jane Doe)',
      'Customer level',
      'Sales POC',
      'Customer Success POC',
      'Collection POC',
      'Invoice level',
      'Sales POC',
      'Someone else…',
    ]);
    await tester.tap(find.text('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Choose the invoice this email is about'), findsNWidgets(2));

    await tester.tap(find.text('Select…'));
    await tester.pumpAndSettle();
    expect(find.text('Search by invoice number'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Search by invoice number'), '0042');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(backend.sent('GET /api/invoices').last.queryParameters['filter'],
        ['invoiceNumber:contains:0042']);
    await tester.tap(find.text('INV-0042'));
    await tester.pumpAndSettle();

    expect(find.text('About: Invoice INV-0042'), findsOneWidget);
    expect(backend.sent('GET /api/emails/context').last.queryParameters['entityId'], 42);
    expect(find.text('Customer emails · ap@acme.com'), findsOneWidget);
    expect(find.text('Sales POC · nobody assigned'), findsOneWidget);
    // The chosen invoice's own Sales POC now names the one person it stores.
    expect(_chipsUnder(tester, 'Invoice level'), ['Sales POC · Divya Nair <divya@company.com>']);
  });

  testWidgets('picking another record never shows the previous record\'s role holders',
      (tester) async {
    final anil = _holder(21, 'Anil Kumar');
    final bala = _holder(22, 'Bala Raman');
    final chitra = _holder(23, 'Chitra Devi');
    final backend = _backend();
    backend.routes['GET /api/emails/context'] = (o) => switch (o.queryParameters['entityId']) {
          42 => _context(collectionPocs: [anil, bala]),
          43 => {
              ..._context(collectionPocs: [chitra]),
              'entityId': 43,
              'entityLabel': 'Invoice INV-0043',
              'entityLink': '/invoices/43',
            },
          _ => _context(withRecord: false),
        };
    backend.routes['GET /api/invoices'] = (o) => {
          'content': [
            {'id': 42, 'invoiceNumber': 'INV-0042', 'customerName': 'Acme Ltd', 'total': 1200},
            {'id': 43, 'invoiceNumber': 'INV-0043', 'customerName': 'Beta Ltd', 'total': 900},
          ],
          'page': 0,
          'size': 20,
          'totalElements': 2,
          'totalPages': 1,
        };
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) => showSendEmailForPickedRecord(context, type: EmailEntityType.invoice),
    );

    Future<void> pick(String opener, String number) async {
      await tester.tap(find.text(opener));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Search by invoice number'), '004');
      await tester.pump(const Duration(milliseconds: 300));
      await tester.pumpAndSettle();
      await tester.tap(find.text(number));
    }

    await pick('Select…', 'INV-0042');
    await tester.pumpAndSettle();
    expect(find.text('Collection POC · Anil Kumar, Bala Raman'), findsOneWidget);

    // The next record's context is slow to come back.
    final answer = Completer<void>();
    backend.held['GET /api/emails/context'] = answer.future;
    await pick('INV-0042', 'INV-0043');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.textContaining('Anil Kumar'), findsNothing);
    expect(find.textContaining('Bala Raman'), findsNothing);
    expect(find.text('Collection POC'), findsOneWidget);
    expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'), 'Collection POC');

    answer.complete();
    await tester.pumpAndSettle();
    expect(find.text('Collection POC · Chitra Devi <chitra@company.com>'), findsOneWidget);
    expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'), 'Collection POC · Chitra Devi <chitra@company.com>');
  });

  testWidgets('a sender role chosen before the record stays on show when that record has no roles',
      (tester) async {
    // A list of users offers the customer seats' roles, since some users are customer logins; an
    // internal user, once picked, offers none (roles: []).
    Map<String, dynamic> role(String key, String label) =>
        {'role': key, 'label': label, 'resolved': null, 'people': [], 'sender': null};
    final backend = _backend();
    backend.routes['GET /api/emails/context'] = (o) {
      final picked = o.queryParameters['entityId'] == 8;
      return {
        ..._context(withRecord: false),
        'entityType': 'USER',
        'entityId': picked ? 8 : null,
        'entityLabel': picked ? 'User sam' : null,
        'entityLink': picked ? '/users/8' : null,
        'roles': picked
            ? []
            : [
                role('CUSTOMER_SUCCESS_POC', 'Customer Success POC'),
                role('COLLECTION_POC', 'Collection POC'),
              ],
        'customerEmails': {'available': !picked, 'addresses': []},
      };
    };
    backend.routes['GET /api/users'] = (o) => {
          'content': [
            {'id': 8, 'username': 'sam', 'fullName': 'Sam Sales', 'email': 'sam@x.com'},
          ],
          'page': 0,
          'size': 20,
          'totalElements': 1,
          'totalPages': 1,
        };
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) => showSendEmailForPickedRecord(context, type: EmailEntityType.user),
    );

    await tester.tap(find.text('Me (Jane Doe)'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Collection POC').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Select…'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('sam'));
    await tester.pumpAndSettle();

    // The From field still says what was chosen, rather than failing to find it among the roles;
    // the server's preview and send then say it is not a role on this user.
    expect(tester.takeException(), isNull);
    expect(find.text('About: User sam'), findsOneWidget);
    expect(find.text('Collection POC'), findsOneWidget);
    expect(find.text('Customer Success POC'), findsNothing);
  });

  group('Gmail (mail-service.md §6)', () {
    const notConnected = 'Jane Doe has not connected Gmail, so this email will be saved in the app '
        'but not sent.';

    testWidgets('the preview\'s warnings show above Send, in amber, and do not stop it',
        (tester) async {
      final backend = _backend(
        context: _context(configured: true, selfGmail: 'NOT_CONNECTED'),
        warnings: [notConnected],
        status: 'NOT_SENT',
      );
      // Tall enough for the whole form, so where the warning sits can be read off the screen.
      await _pump(tester, backend: backend, size: const Size(1366, 1100), onOpen: _openInvoice);

      await tester.tap(find.text('Customer emails · ap@acme.com'));
      await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Reminder');
      await _settlePreview(tester);

      final warning = find.text(notConnected);
      expect(warning, findsOneWidget);
      final box = tester.widget<Container>(
          find.ancestor(of: warning, matching: find.byType(Container)).first);
      expect((box.decoration as BoxDecoration).color, Colors.amber.shade100);
      // Below the whole form, right above the buttons.
      expect(tester.getRect(warning).top,
          greaterThan(tester.getRect(find.widgetWithText(TextFormField, 'Message')).bottom));
      expect(tester.getRect(warning).bottom, lessThan(tester.getRect(find.text('Send')).top));
      expect(_sendButton(tester, 'Send').onPressed, isNotNull);

      await tester.tap(find.text('Send'));
      await tester.pumpAndSettle();
      expect(backend.sent('POST /api/emails'), hasLength(1));
      expect(find.text('Email saved — not sent: No Gmail'), findsOneWidget);
    });

    testWidgets('writing as myself without a working Gmail, the form offers to connect it',
        (tester) async {
      const line = 'Connect your Gmail to send email';
      for (final gmail in ['NOT_CONNECTED', 'NEEDS_RECONNECT']) {
        await _pump(tester,
            backend: _backend(context: _context(configured: true, selfGmail: gmail)),
            onOpen: _openInvoice);
        expect(find.text(line), findsOneWidget, reason: gmail);
        expect(find.widgetWithText(TextButton, 'Connect'), findsOneWidget, reason: gmail);

        // Another sender sends from their own Gmail, not mine.
        await tester.tap(find.text('Me (Jane Doe)'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Collection POC · Bob Smith <bob@company.com>').last);
        await tester.pumpAndSettle();
        expect(find.text(line), findsNothing, reason: gmail);
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }

      // Connected; no mail service at all (the notice says so); a customer login, who does not
      // connect Gmail.
      for (final (context, user) in [
        (_context(configured: true, selfGmail: 'CONNECTED'), _staff),
        (_context(configured: false, selfGmail: 'NOT_CONNECTED'), _staff),
        (_context(configured: true, selfGmail: 'NOT_CONNECTED', restricted: true), _customerLogin),
      ]) {
        await _pump(tester, backend: _backend(context: context), user: user, onOpen: _openInvoice);
        expect(find.text('Send email'), findsOneWidget);
        expect(find.text(line), findsNothing);
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }

      // The list page's form and the bulk form offer it too.
      for (final open in <Future<void> Function(BuildContext)>[
        (context) => showSendEmailForPickedRecord(context, type: EmailEntityType.invoice),
        (context) => sendEmailBulkAction(EmailEntityType.invoice).buildParams!(context),
      ]) {
        await _pump(tester,
            backend: _backend(
                context: _context(withRecord: false, configured: true, selfGmail: 'NOT_CONNECTED')),
            onOpen: open);
        expect(find.text(line), findsOneWidget);
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }
    });

    testWidgets('Connect leaves the form for the Gmail page, asking first about what is written',
        (tester) async {
      tester.view.physicalSize = const Size(1366, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final backend = _backend(context: _context(configured: true, selfGmail: 'NOT_CONNECTED'));
      EmailComposeOutcome? result;
      final router = GoRouter(routes: [
        GoRoute(
          path: '/',
          builder: (_, __) => Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => _openInvoice(context, onResult: (r) => result = r),
                child: const Text('open'),
              ),
            ),
          ),
        ),
        GoRoute(path: '/me/gmail', builder: (_, __) => const Text('gmail page')),
      ]);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(backend.dio),
          currentUserProvider.overrideWithValue(_staff),
        ],
        child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Half written');
      await tester.tap(find.widgetWithText(TextButton, 'Connect'));
      await tester.pumpAndSettle();
      expect(find.text('Leave this email?'), findsOneWidget);
      await tester.tap(find.text('Stay'));
      await tester.pumpAndSettle();
      expect(find.text('Half written'), findsOneWidget);
      expect(find.text('gmail page'), findsNothing);

      await tester.tap(find.widgetWithText(TextButton, 'Connect'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Leave'));
      await tester.pumpAndSettle();
      expect(find.text('gmail page'), findsOneWidget);
      expect(find.text('Send email'), findsNothing);
      expect(result, EmailComposeOutcome.leftForGmail);
      expect(backend.sent('POST /api/emails'), isEmpty);
    });

    testWidgets('a sender whose Gmail is not connected says so after their name', (tester) async {
      final unconnected = {..._bob, 'gmail': 'NEEDS_RECONNECT'};
      final backend = _backend(
          context: _context(
              configured: true, selfGmail: 'CONNECTED', collectionPocs: [unconnected]));
      backend.routes['GET /api/emails/people'] = (_) => [
            {
              'userId': 7,
              'name': 'Ann Admin',
              'username': 'ann',
              'email': 'ann@company.com',
              'gmail': 'NOT_CONNECTED',
            },
          ];
      await _pump(tester, backend: backend, onOpen: _openInvoice);

      expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'),
          'Collection POC · Bob Smith <bob@company.com> · Gmail not connected');
      expect(_fromItemText(tester, 'self'), 'Me (Jane Doe)');
      // In To the role reaches its people, whoever sends: no note there.
      expect(find.widgetWithText(ActionChip, 'Collection POC · Bob Smith <bob@company.com>'),
          findsOneWidget);

      await tester.tap(find.text('Me (Jane Doe)'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Someone else…').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Ann Admin'));
      await tester.pumpAndSettle();
      expect(_fromItemText(tester, 'user:7'), 'Ann Admin <ann@company.com> · Gmail not connected');
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      // A connected holder, and no mail service at all, say nothing more.
      for (final context in [
        _context(configured: true, collectionPocs: [
          {..._bob, 'gmail': 'CONNECTED'},
        ]),
        _context(configured: false, collectionPocs: [unconnected]),
      ]) {
        await _pump(tester, backend: _backend(context: context), onOpen: _openInvoice);
        expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'),
            'Collection POC · Bob Smith <bob@company.com>');
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }
    });

    testWidgets('the Gmail marker survives a name too long for the row', (tester) async {
      // A real name and address, wider than the From field at either size, so something must be
      // cut: the marker is what makes the item worth reading, so it is not what goes.
      const carlos = {
        'userId': 12,
        'name': 'Carlos Duarte',
        'email': 'carlos.collect.e2e2@gmail.com',
        'gmail': 'NOT_CONNECTED',
      };
      const marker = ' · Gmail not connected';
      for (final size in [const Size(1366, 900), const Size(390, 844)]) {
        final reason = 'at ${size.width.toInt()} px';
        await _pump(tester,
            backend: _backend(
                context: _context(
                    configured: true, selfGmail: 'CONNECTED', collectionPocs: [carlos])),
            size: size,
            onOpen: _openInvoice);
        expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'),
            'Collection POC · Carlos Duarte <carlos.collect.e2e2@gmail.com>$marker',
            reason: reason);

        await tester.tap(find.byType(DropdownButton<String>));
        await tester.pumpAndSettle();
        // In the open menu the name gives way — shortened where there is room for some of it,
        // dropped where the marker alone fills the row — and nothing overflows either way.
        final note = tester.renderObject<RenderParagraph>(find.text(marker).last);
        final nameFinder =
            find.text('Collection POC · Carlos Duarte <carlos.collect.e2e2@gmail.com>');
        if (size.width > 800) {
          // Room for some of the name: it is cut to fit and the marker stays whole beside it.
          final name = tester.renderObject<RenderParagraph>(nameFinder.last);
          expect(name.size.width, lessThan(name.getMaxIntrinsicWidth(double.infinity)),
              reason: reason);
          expect(note.size.width, closeTo(note.getMaxIntrinsicWidth(double.infinity), 0.5),
              reason: reason);
        } else {
          // A phone row is narrower than the marker itself: the name goes altogether and the
          // marker takes the row, rather than being pushed off the end of it.
          expect(note.size.width, greaterThan(0), reason: reason);
          expect(note.size.width, lessThanOrEqualTo(size.width), reason: reason);
        }
        expect(tester.takeException(), isNull, reason: reason);

        // Chosen, the closed field says it too.
        await tester.tap(find.text(marker).last);
        await tester.pumpAndSettle();
        expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'),
            'Collection POC · Carlos Duarte <carlos.collect.e2e2@gmail.com>$marker',
            reason: reason);
        expect(tester.takeException(), isNull, reason: reason);

        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }
    });

    test('a person\'s Gmail comes with them, and says whether their email would go out', () {
      expect(EmailPerson.fromJson(_bob).gmail, isNull);
      expect(EmailPerson.fromJson(_bob).gmailNotConnected, isFalse);
      bool unconnected(String gmail) =>
          EmailPerson.fromJson({..._bob, 'gmail': gmail}).gmailNotConnected;
      expect(unconnected('CONNECTED'), isFalse);
      expect(unconnected('NEEDS_RECONNECT'), isTrue);
      expect(unconnected('NOT_CONNECTED'), isTrue);
      final ctx = EmailContext.fromJson(_context(configured: true, selfGmail: 'NOT_CONNECTED'));
      expect(ctx.delivery.configured, isTrue);
      expect(ctx.self.gmail, 'NOT_CONNECTED');
      final preview = EmailPreview.fromJson(const {'to': [], 'problems': [], 'warnings': ['w']});
      expect(preview.warnings, ['w']);
      expect(EmailPreview.fromJson(const {'to': []}).warnings, isEmpty);
    });
  });

  testWidgets('bulk mode hands back the parameters for each record instead of sending',
      (tester) async {
    final backend = _backend(context: _context(withRecord: false));
    Map<String, dynamic>? params;
    await _pump(
      tester,
      backend: backend,
      onOpen: (context) async =>
          params = await sendEmailBulkAction(EmailEntityType.invoice).buildParams!(context),
    );

    expect(find.text('Selected invoices — a separate email for each'), findsOneWidget);
    // Both groups are offered without a record, each chip standing for every selected row (§4).
    expect(_chipsUnder(tester, 'Customer level'), [
      "Sales POC (each record's)",
      "Customer Success POC (each record's)",
      "Collection POC (each record's)",
    ]);
    expect(_chipsUnder(tester, 'Invoice level'), ["Sales POC (each record's)"]);
    expect(_fromMenu(tester), [
      'Me (Jane Doe)',
      'Customer level',
      "Sales POC (each record's)",
      "Customer Success POC (each record's)",
      "Collection POC (each record's)",
      'Invoice level',
      "Sales POC (each record's)",
      'Someone else…',
    ]);
    expect(_fromItemText(tester, 'role:COLLECTION_POC:CUSTOMER'), "Collection POC (each record's)");

    // The two Sales POC lines of the menu read alike under their headings, and bulk runs no
    // preview, so the closed field is the only thing that says whose POC of each row would send.
    await _chooseFrom(tester, 'Me (Jane Doe)', "Sales POC (each record's)");
    expect(_closedFrom(tester), "Sales POC (each customer's)");
    await _chooseFrom(tester, "Sales POC (each customer's)", "Sales POC (each record's)", at: 1);
    expect(_closedFrom(tester), "Sales POC (each invoice's)");
    await _chooseFrom(tester, "Sales POC (each invoice's)", 'Me (Jane Doe)');

    await tester.tap(find.widgetWithText(ActionChip, "Collection POC (each record's)"));
    await tester.pumpAndSettle();
    await tester.tap(_chipIn('Invoice level', "Sales POC (each record's)"));
    await tester.pumpAndSettle();

    // Nobody is named in bulk, so the two Sales POCs in the box would read exactly alike: each
    // says whose POC every row's would be instead.
    await tester.tap(_chipIn('Customer level', "Sales POC (each record's)"));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip, "Sales POC (each customer's)"), findsOneWidget);
    expect(find.widgetWithText(InputChip, "Sales POC (each invoice's)"), findsOneWidget);
    // Alone again, the one left is the plain role.
    await tester.tap(find.descendant(
        of: find.widgetWithText(InputChip, "Sales POC (each customer's)"),
        matching: find.byTooltip('Remove')));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip, "Sales POC (each record's)"), findsOneWidget);

    await tester.tap(find.text("Customer emails (each record's)"));
    await tester.enterText(find.widgetWithText(TextFormField, 'Subject *'), 'Statement');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();

    expect(params, {
      'entityType': 'INVOICE',
      'to': [
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
        {'type': 'ROLE', 'role': 'SALES_POC', 'level': 'RECORD'},
        {'type': 'CUSTOMER'},
      ],
      'subject': 'Statement',
    });
    expect(backend.sent('POST /api/emails/preview'), isEmpty);
    expect(backend.sent('POST /api/emails'), isEmpty);

    final spec = sendEmailBulkAction(EmailEntityType.invoice);
    expect(spec.endpoint, '/api/emails/bulk');
    expect(spec.successMessage!(3), 'Email queued for 3 records');
  });
}
