import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/disputes/dispute_create_dialog.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/disputes/disputes_screen.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_form_dialog.dart';
import 'package:gene_invoice/features/promises/promises_screen.dart';
import 'package:gene_invoice/features/promises/promises_tab.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/dispute.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/promise.dart';
import 'package:go_router/go_router.dart';

const _email = {Privileges.emailView, Privileges.emailSend};

CurrentUser _staff(Set<String> privileges, {String role = 'ADMIN'}) => CurrentUser(
      id: 1,
      username: 'admin',
      fullName: 'Ada Admin',
      role: role,
      privileges: privileges,
      customerId: null,
    );

final _admin = _staff({
  Privileges.promiseView,
  Privileges.promiseManage,
  Privileges.promiseOverride,
  Privileges.disputeView,
  Privileges.disputeManage,
  Privileges.auditView,
  Privileges.pocView,
  ..._email,
});

CurrentUser _customerLogin(Set<String> privileges) => CurrentUser(
      id: 30,
      username: 'acme',
      fullName: 'Acme Buyer',
      role: 'CUSTOMER',
      privileges: privileges,
      customerId: 5,
    );

Map<String, dynamic> _promise({List<Map<String, dynamic>> invoices = const []}) => {
      'id': 77,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 1200,
      'fulfilledAmount': 200,
      'remainingAmount': 1000,
      'promisedDate': '2026-09-30',
      'status': 'OPEN',
      'statusOverridden': false,
      'collectionPoc': {'id': 12, 'username': 'bob', 'fullName': 'Bob Smith'},
      'notes': 'Paying after the audit',
      'invoices': invoices,
      'payments': [],
    };

Map<String, dynamic> _dispute({String status = 'PENDING', String? proposedChangeJson}) => {
      'id': 12,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'openedByUserId': 30,
      'targetType': 'INVOICE',
      'targetId': 42,
      'targetSummary': 'Invoice INV-0042',
      'targetNumber': 'INV-0042',
      'targetAmount': 1200,
      'reason': 'Charged twice',
      'proposedChangeJson': proposedChangeJson,
      'status': status,
      'adminNotes': null,
      'resolvedByUserId': null,
      'resolvedAt': null,
      'createdAt': '2026-09-15T09:00:00Z',
      'updatedAt': null,
    };

/// [selfGmail] is the writer's own Gmail connection, when the server names it.
Map<String, dynamic> _emailContext(String type, int id,
        {bool restricted = false, String? selfGmail}) =>
    {
      'entityType': type,
      'entityId': id,
      'entityLabel': '${type == 'PROMISE' ? 'Promise' : 'Dispute'} #$id',
      'entityLink': null,
      'delivery': {'configured': true, 'mailbox': 'billing@company.com'},
      'sender': {
        'restricted': restricted,
        'self': {
          'userId': 1,
          'name': restricted ? 'Acme Buyer' : 'Ada Admin',
          'email': null,
          if (selfGmail != null) 'gmail': selfGmail,
        },
      },
      'roles': [],
      'customerEmails': {'available': true, 'addresses': []},
      'suggestion': null,
    };

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

/// A fake backend: each "METHOD /path" answers with what its handler returns, and every request
/// is kept so a test can read what the screen sent. Anything unrouted is a 404.
class _Backend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  _Backend(this.routes);

  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      requests.add(options);
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

/// A common laptop screen, where a detail page's top pane may take only about 390 px.
const _laptop = Size(1366, 768);

/// The app's routes that these screens move between, with stand-ins for the pages they lead to.
/// [shellAppBar] adds the app shell's 56 px AppBar above the page, for tests that measure what
/// fits on screen.
Future<GoRouter> _pumpApp(
  WidgetTester tester, {
  required _Backend backend,
  required CurrentUser user,
  required String initialLocation,
  Widget Function(BuildContext context)? home,
  Size size = const Size(1366, 900),
  bool shellAppBar = false,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: initialLocation,
    routes: [
      ShellRoute(
        builder: (context, state, child) => Scaffold(
          appBar: shellAppBar ? AppBar(title: const Text('Gene Invoice')) : null,
          body: child,
        ),
        routes: [
          GoRoute(path: '/home', builder: (context, _) => home!(context)),
          GoRoute(
            path: '/promises',
            builder: (_, __) => const PromisesScreen(query: TableQuery(size: 20)),
          ),
          GoRoute(
            path: '/promises/:id',
            builder: (_, s) => PromiseDetailScreen(
              id: int.parse(s.pathParameters['id']!),
              initialTab: s.uri.queryParameters['tab'],
            ),
          ),
          GoRoute(
            path: '/disputes',
            builder: (_, __) => const DisputesScreen(query: TableQuery(size: 20)),
          ),
          GoRoute(
            path: '/disputes/:id',
            builder: (_, s) => DisputeDetailScreen(
              key: ValueKey('dispute-${s.pathParameters['id']}'),
              id: int.parse(s.pathParameters['id']!),
              initialTab: s.uri.queryParameters['tab'],
            ),
          ),
          GoRoute(path: '/invoices/:id', builder: (_, __) => const Text('invoice page')),
          GoRoute(path: '/me/gmail', builder: (_, __) => const Text('gmail page')),
        ],
      ),
    ],
  );
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
    ],
    child: MaterialApp.router(theme: AppTheme.light(), routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return router;
}

/// A page with one button that runs [onOpen], for the dialogs that callers open.
Widget Function(BuildContext) _opener(Future<void> Function(BuildContext context) onOpen) =>
    (context) => Center(
          child: TextButton(onPressed: () => onOpen(context), child: const Text('open')),
        );

List<String> _tabLabels(WidgetTester tester) =>
    tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text ?? '').toList();

Map<String, dynamic> _query(RequestOptions r) => r.queryParameters;

void main() {
  group('promise form', () {
    _Backend backend() => _Backend({
          'GET /api/customers/5/pocs': (_) => [],
          'GET /api/invoices': (_) => _page([]),
          'GET /api/pocs/assignable': (_) => [],
          'POST /api/promises': (_) => _promise(),
          'PUT /api/promises/77': (_) => _promise(),
          'GET /api/emails/context': (r) => _emailContext('PROMISE', 77),
        });

    testWidgets('offers "Notify through email" only to someone who may send', (tester) async {
      for (final (user, shown) in [
        (_staff({Privileges.promiseManage}), false),
        (_staff({Privileges.promiseManage, ..._email}), true),
      ]) {
        await _pumpApp(tester,
            backend: backend(),
            user: user,
            initialLocation: '/home',
            home: _opener((context) =>
                showPromiseDialog(context: context, customerId: 5, customerName: 'Acme Ltd')));
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        expect(find.text('Raise promise'), findsOneWidget);
        expect(find.text('Notify through email'), shown ? findsOneWidget : findsNothing);
      }
    });

    testWidgets('a new promise, then an edited one, each open the email form for that promise',
        (tester) async {
      for (final editing in [false, true]) {
        final api = backend();
        bool? result;
        await _pumpApp(tester,
            backend: api,
            user: _admin,
            initialLocation: '/home',
            home: _opener((context) async {
              result = await showPromiseDialog(
                context: context,
                customerId: 5,
                customerName: 'Acme Ltd',
                existing: editing ? PaymentPromise.fromJson(_promise()) : null,
              );
            }));
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();

        if (!editing) await tester.enterText(find.byType(TextField).first, '1200');
        await tester.tap(find.text('Notify through email'));
        await tester.tap(find.text(editing ? 'Save' : 'Raise promise'));
        await tester.pumpAndSettle();

        expect(api.sent(editing ? 'PUT /api/promises/77' : 'POST /api/promises'), hasLength(1));
        // The form has closed and the compose form is open for the saved promise.
        expect(find.text('Notify through email'), findsNothing);
        expect(find.text('Send email'), findsOneWidget);
        final ctx = api.sent('GET /api/emails/context').single;
        expect(_query(ctx)['entityType'], 'PROMISE');
        expect(_query(ctx)['entityId'], 77);
        expect(_query(ctx)['event'], editing ? 'UPDATED' : 'CREATED');
        // The caller hears of the save once the email form closes, whether or not it sent.
        expect(result, isNull);

        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
        expect(result, isTrue);
      }
    });

    testWidgets('unticked, saving opens nothing more', (tester) async {
      final api = backend();
      bool? result;
      await _pumpApp(tester,
          backend: api,
          user: _admin,
          initialLocation: '/home',
          home: _opener((context) async {
            result = await showPromiseDialog(context: context, customerId: 5);
          }));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).first, '500');
      await tester.tap(find.text('Raise promise'));
      await tester.pumpAndSettle();

      expect(result, isTrue);
      expect(find.text('Send email'), findsNothing);
      expect(api.sent('GET /api/emails/context'), isEmpty);
    });
  });

  testWidgets('a customer login filing a dispute may notify, and writes as themselves',
      (tester) async {
    final api = _Backend({
      'POST /api/disputes': (_) => _dispute(),
      'GET /api/emails/context': (_) => _emailContext('DISPUTE', 12, restricted: true),
    });
    bool? result;
    await _pumpApp(tester,
        backend: api,
        user: _customerLogin({Privileges.disputeCreate, Privileges.disputeView, ..._email}),
        initialLocation: '/home',
        size: const Size(400, 800),
        home: _opener((context) async {
          result = await showDisputeDialog(
            context: context,
            targetType: DisputeTargetType.INVOICE,
            targetId: 42,
            targetLabel: 'INV-0042',
          );
        }));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextFormField).first, 'Charged twice');
    await tester.tap(find.text('Notify through email'));
    await tester.tap(find.text('Submit'));
    await tester.pumpAndSettle();

    final ctx = api.sent('GET /api/emails/context').single;
    expect(_query(ctx)['entityType'], 'DISPUTE');
    expect(_query(ctx)['entityId'], 12);
    expect(_query(ctx)['event'], 'CREATED');
    expect(find.text('Send email'), findsOneWidget);
    expect(find.text('You'), findsOneWidget);

    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(result, isTrue);
    expect(tester.takeException(), isNull);
  });

  group('dispute page', () {
    testWidgets('has Send email and an Email tab after the record history', (tester) async {
      await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) => _dispute(status: 'APPROVED'),
            'GET /api/audit': (_) => [],
          }),
          user: _admin,
          initialLocation: '/disputes/12');

      expect(find.text('Dispute #12'), findsOneWidget);
      expect(find.text('Invoice INV-0042 — ₹1,200.00'), findsOneWidget);
      expect(find.text('Send email'), findsOneWidget);
      expect(_tabLabels(tester), ['Invoice history', 'Email']);
      // Resolved already: no panel, so nothing to notify about.
      expect(find.text('Approve'), findsNothing);
      expect(find.text('Notify through email'), findsNothing);
    });

    testWidgets('without email privileges there is neither the button nor the tab',
        (tester) async {
      await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) => _dispute(),
            'GET /api/audit': (_) => [],
          }),
          user: _staff({Privileges.disputeView, Privileges.auditView}, role: 'VIEWER'),
          initialLocation: '/disputes/12');

      expect(find.text('Send email'), findsNothing);
      expect(_tabLabels(tester), ['Invoice history']);
    });

    testWidgets('the resolution panel fits a phone', (tester) async {
      await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) => _dispute(),
            'GET /api/audit': (_) => [],
          }),
          user: _admin,
          initialLocation: '/disputes/12',
          size: const Size(400, 800));

      expect(find.text('Send email'), findsOneWidget);
      expect(find.text('Notify through email'), findsOneWidget);
      await tester.scrollUntilVisible(find.text('Approve'), 100,
          scrollable: find.byType(Scrollable).first);
      expect(find.text('Deny'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('on a laptop screen an admin reaches Approve and Deny without scrolling',
        (tester) async {
      await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) =>
                _dispute(proposedChangeJson: '{"action":"update_amount","amount":500.0}'),
            'GET /api/audit': (_) => [],
          }),
          user: _admin,
          initialLocation: '/disputes/12',
          size: _laptop,
          shellAppBar: true);

      for (final text in ['Notify through email', 'Approve', 'Deny']) {
        final shown = find.text(text).hitTestable();
        expect(shown, findsOneWidget, reason: text);
        expect(tester.getRect(shown).bottom, lessThanOrEqualTo(_laptop.height), reason: text);
      }
      // Resolving is what a pending dispute waits for, so that tab comes first and opens; the
      // dispute's own facts stay above the tabs.
      expect(_tabLabels(tester), ['Resolve', 'Invoice history', 'Email']);
      expect(find.text('Charged twice').hitTestable(), findsOneWidget);
      // The proposed change once, in the tab beside the applied change that starts as its copy.
      expect(find.text('Proposed change').hitTestable(), findsOneWidget);
      expect(find.textContaining('"amount": 500.0'), findsNWidgets(2));
      expect(tester.takeException(), isNull);
    });

    testWidgets('a customer login sees their pending dispute but nothing to resolve',
        (tester) async {
      await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) =>
                _dispute(proposedChangeJson: '{"action":"update_amount","amount":500.0}'),
          }),
          user: _customerLogin({Privileges.disputeView, Privileges.disputeCreate, ..._email}),
          initialLocation: '/disputes/12',
          size: _laptop,
          shellAppBar: true);

      expect(find.text('Charged twice'), findsOneWidget);
      expect(find.textContaining('"amount": 500.0'), findsOneWidget);
      for (final text in ['Approve', 'Deny', 'Notify through email', 'Applied change (JSON)']) {
        expect(find.text(text), findsNothing, reason: text);
      }
      expect(_tabLabels(tester), ['Email']);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a link to another tab opens it, with Resolve one tap away', (tester) async {
      final router = await _pumpApp(tester,
          backend: _Backend({
            'GET /api/disputes/12': (_) => _dispute(),
            'GET /api/audit': (_) => [],
          }),
          user: _admin,
          initialLocation: '/disputes/12?tab=history',
          size: _laptop,
          shellAppBar: true);

      expect(_tabLabels(tester), ['Resolve', 'Invoice history', 'Email']);
      expect(find.text('Approve').hitTestable(), findsNothing);

      await tester.tap(find.text('Resolve'));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.toString(), '/disputes/12?tab=resolve');
      expect(find.text('Approve').hitTestable(), findsOneWidget);
    });

    testWidgets('the Resolve tab refuses malformed JSON on approve, and Deny posts the notes',
        (tester) async {
      var status = 'PENDING';
      final api = _Backend({
        'GET /api/disputes/12': (_) => _dispute(status: status),
        'GET /api/audit': (_) => [],
        'POST /api/disputes/12/deny': (_) {
          status = 'DENIED';
          return _dispute(status: status);
        },
        'GET /api/disputes': (_) => _page([_dispute(status: 'DENIED')]),
        'GET /api/table-schemas/disputes': (_) => {'entity': 'disputes', 'columns': []},
      });
      final router = await _pumpApp(tester,
          backend: api,
          user: _admin,
          initialLocation: '/disputes/12',
          size: _laptop,
          shellAppBar: true);

      await tester.enterText(find.byType(TextField).first, '{not json');
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();
      expect(api.sent('POST /api/disputes/12/approve'), isEmpty);
      expect(find.textContaining('FormatException').hitTestable(), findsOneWidget);
      expect(router.routerDelegate.currentConfiguration.uri.path, '/disputes/12');

      // Deny applies nothing, so the malformed JSON does not stop it.
      await tester.enterText(find.byType(TextField).last, 'Not a duplicate');
      await tester.tap(find.text('Deny'));
      await tester.pumpAndSettle();
      expect(api.sent('POST /api/disputes/12/deny').single.data, {'adminNotes': 'Not a duplicate'});
      expect(router.routerDelegate.currentConfiguration.uri.path, '/disputes');
    });

    testWidgets('someone who may see neither tab still gets the dispute', (tester) async {
      await _pumpApp(tester,
          backend: _Backend({'GET /api/disputes/12': (_) => _dispute()}),
          user: _staff({Privileges.disputeView}, role: 'VIEWER'),
          initialLocation: '/disputes/12');

      expect(find.text('Charged twice'), findsOneWidget);
      expect(_tabLabels(tester), isEmpty);
      expect(tester.takeException(), isNull);
    });

    testWidgets('resolving with notify ticked writes the email before returning to the list',
        (tester) async {
      var status = 'PENDING';
      final api = _Backend({
        'GET /api/disputes/12': (_) => _dispute(status: status),
        'GET /api/audit': (_) => [],
        'POST /api/disputes/12/approve': (_) {
          status = 'APPROVED';
          return _dispute(status: status);
        },
        'GET /api/emails/context': (_) => _emailContext('DISPUTE', 12),
        'GET /api/disputes': (_) => _page([_dispute(status: 'APPROVED')]),
        'GET /api/table-schemas/disputes': (_) => {'entity': 'disputes', 'columns': []},
      });
      final router = await _pumpApp(tester,
          backend: api,
          user: _admin,
          initialLocation: '/disputes/12',
          size: _laptop,
          shellAppBar: true);

      // The applied change first, then the admin notes.
      await tester.enterText(find.byType(TextField).last, 'Refund issued');
      await tester.tap(find.text('Notify through email'));
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(api.sent('POST /api/disputes/12/approve').single.data,
          {'adminNotes': 'Refund issued'});
      final ctx = api.sent('GET /api/emails/context').single;
      expect(_query(ctx)['entityType'], 'DISPUTE');
      expect(_query(ctx)['entityId'], 12);
      expect(_query(ctx)['event'], 'UPDATED');
      // Still on the dispute, which now reads as approved, while the email is written.
      expect(router.routerDelegate.currentConfiguration.uri.path, '/disputes/12');
      expect(find.text('About: Dispute #12'), findsOneWidget);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/disputes');
    });

    testWidgets('Connect in the compose form after a decision leads to the Gmail page, not the list',
        (tester) async {
      var status = 'PENDING';
      final api = _Backend({
        'GET /api/disputes/12': (_) => _dispute(status: status),
        'GET /api/audit': (_) => [],
        'POST /api/disputes/12/deny': (_) {
          status = 'DENIED';
          return _dispute(status: status);
        },
        'GET /api/emails/context': (_) =>
            _emailContext('DISPUTE', 12, selfGmail: 'NEEDS_RECONNECT'),
        'GET /api/disputes': (_) => _page([_dispute(status: 'DENIED')]),
        'GET /api/table-schemas/disputes': (_) => {'entity': 'disputes', 'columns': []},
      });
      final router = await _pumpApp(tester,
          backend: api, user: _admin, initialLocation: '/disputes/12', size: _laptop);

      await tester.tap(find.text('Notify through email'));
      await tester.tap(find.text('Deny'));
      await tester.pumpAndSettle();
      expect(find.text('About: Dispute #12'), findsOneWidget);

      // Nothing written yet, so nothing to ask about.
      await tester.tap(find.widgetWithText(TextButton, 'Connect'));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/me/gmail');
      expect(find.text('gmail page'), findsOneWidget);
      expect(api.sent('POST /api/disputes/12/deny'), hasLength(1));
    });
  });

  group('promise page', () {
    _Backend backend() => _Backend({
          'GET /api/promises/77': (_) => _promise(invoices: [
                {
                  'id': 42,
                  'invoiceNumber': 'INV-0042',
                  'total': 1200,
                  'balance': 1000,
                  'status': 'PARTIALLY_PAID',
                },
              ]),
          'GET /api/audit': (_) => [],
        });

    testWidgets('shows the promise, its actions, and History then Email tabs', (tester) async {
      await _pumpApp(tester, backend: backend(), user: _admin, initialLocation: '/promises/77');

      expect(find.text('Promise #77'), findsOneWidget);
      expect(find.text('Acme Ltd'), findsOneWidget);
      expect(find.text('₹1,200.00'), findsOneWidget);
      expect(find.text('₹1,000.00'), findsOneWidget);
      expect(find.text('Open'), findsOneWidget); // the status chip
      expect(find.text('Bob Smith'), findsOneWidget);
      expect(find.text('INV-0042'), findsOneWidget);
      expect(find.text(' • ₹1,000.00 left'), findsOneWidget);
      expect(find.text('Paying after the audit'), findsOneWidget);
      for (final action in ['Edit', 'Override status', 'Cancel', 'Send email']) {
        expect(find.text(action), findsOneWidget, reason: action);
      }
      expect(_tabLabels(tester), ['History', 'Email']);

      // Every action still has room on a phone.
      tester.view.physicalSize = const Size(400, 800);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('Send email'), findsOneWidget);

      await tester.tap(find.text('INV-0042'));
      await tester.pumpAndSettle();
      expect(find.text('invoice page'), findsOneWidget);
    });

    testWidgets('a customer login sees no POC and no staff actions; a phone lays it out',
        (tester) async {
      await _pumpApp(tester,
          backend: _Backend({'GET /api/promises/77': (_) => _promise()}),
          user: _customerLogin({Privileges.promiseView, Privileges.emailView}),
          initialLocation: '/promises/77',
          size: const Size(400, 800));

      expect(find.text('Collection POC'), findsNothing);
      expect(find.text('Bob Smith'), findsNothing);
      expect(find.text('General promise — against the account balance'), findsOneWidget);
      for (final action in ['Edit', 'Override status', 'Cancel', 'Send email']) {
        expect(find.text(action), findsNothing, reason: action);
      }
      expect(_tabLabels(tester), ['Email']);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a missing promise reads as not found', (tester) async {
      await _pumpApp(tester,
          backend: _Backend({}), user: _admin, initialLocation: '/promises/77');
      expect(find.text('That promise does not exist.'), findsOneWidget);
    });

    testWidgets("a promise card's Open link leads to the promise's page", (tester) async {
      final api = backend()
        ..routes['GET /api/promises'] = (_) => _page([_promise()]);
      final router = await _pumpApp(tester,
          backend: api,
          user: _admin,
          initialLocation: '/home',
          home: (_) => const PromisesTab(customerId: 5, customerName: 'Acme Ltd'));

      await tester.tap(find.widgetWithText(TextButton, 'Open'));
      await tester.pumpAndSettle();
      expect(router.routerDelegate.currentConfiguration.uri.path, '/promises/77');
      expect(find.text('Promise #77'), findsOneWidget);
    });
  });

  group('lists', () {
    _Backend backend() => _Backend({
          'GET /api/table-schemas/promises': (_) => {'entity': 'promises', 'columns': []},
          'GET /api/promises': (_) => _page([_promise()]),
          'GET /api/promises/summary': (_) => {'total': 1},
          'GET /api/promises/77': (_) => _promise(),
          'GET /api/table-schemas/disputes': (_) => {'entity': 'disputes', 'columns': []},
          'GET /api/disputes': (_) => _page([_dispute()]),
          'GET /api/disputes/12': (_) => _dispute(),
          'GET /api/audit': (_) => [],
        });

    for (final (list, row, details) in [
      ('/promises', 'Acme Ltd', '/promises/77'),
      ('/disputes', 'Charged twice', '/disputes/12'),
    ]) {
      testWidgets('$list: a sender gets the email actions, and a row opens its page',
          (tester) async {
        final router = await _pumpApp(tester,
            backend: backend(),
            user: _staff({Privileges.promiseView, Privileges.disputeView, ..._email},
                role: 'VIEWER'),
            initialLocation: list);

        expect(find.widgetWithText(OutlinedButton, 'Send email'), findsOneWidget);
        expect(find.byTooltip('Send email'), findsOneWidget);
        // A selection offers sending one email per row beside the page's own button.
        await tester.tap(find.byType(Checkbox).last);
        await tester.pumpAndSettle();
        expect(find.text('Send email'), findsNWidgets(2));
        await tester.tap(find.byType(Checkbox).last);
        await tester.pumpAndSettle();

        await tester.tap(find.text(row).first);
        await tester.pumpAndSettle();
        expect(router.routerDelegate.currentConfiguration.uri.path, details);
      });

      testWidgets('$list: without EMAIL_SEND there are no email actions', (tester) async {
        await _pumpApp(tester,
            backend: backend(),
            user: _staff({Privileges.promiseView, Privileges.disputeView}, role: 'VIEWER'),
            initialLocation: list);

        expect(find.text('Send email'), findsNothing);
        expect(find.byTooltip('Send email'), findsNothing);
      });
    }
  });
}
