import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_models.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/customers/customer_detail_screen.dart';
import 'package:gene_invoice/features/customers/customers_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_detail_screen.dart';
import 'package:gene_invoice/features/invoices/invoice_form_screen.dart';
import 'package:gene_invoice/features/payments/payment_detail_screen.dart';
import 'package:gene_invoice/features/payments/record_payment_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/customer.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:go_router/go_router.dart';

// The email feature as the customer, invoice and payment screens carry it: the list and details
// actions, and the notify box on each create form opening the compose for the new record.

CurrentUser _user(Set<String> privileges, {int? customerId}) => CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
    );

/// A fake backend: each "METHOD /path" answers with what its handler returns, anything else is a
/// 404, and every request is kept so a test can read what was asked.
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

Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
      'content': rows,
      'page': 0,
      'size': 20,
      'totalElements': rows.length,
      'totalPages': 1,
    };

const _acme = {'id': 5, 'name': 'Acme Ltd', 'email': 'ap@acme.com', 'creditBalance': 0};

/// The compose form's routes, for a record of [type] that the server suggests writing to the
/// customer about. [selfGmail] is the writer's own Gmail connection, when the server names it.
Map<String, Object? Function(RequestOptions)> _composeRoutes(String type, String label,
        {String? selfGmail}) =>
    {
      'GET /api/emails/context': (r) => {
            'entityType': type,
            'entityId': r.queryParameters['entityId'],
            'entityLabel': label,
            'delivery': {'configured': true, 'mailbox': 'billing@company.com'},
            'sender': {
              'restricted': false,
              'self': {
                'userId': 3,
                'name': 'Jane Doe',
                'email': 'jane@company.com',
                if (selfGmail != null) 'gmail': selfGmail,
              },
            },
            'roles': [],
            'customerEmails': {
              'available': true,
              'addresses': [
                {'name': 'Acme Ltd', 'address': 'ap@acme.com'},
              ],
            },
            'suggestion': {
              'subject': 'About $label',
              'body': '',
              'to': [
                {'type': 'CUSTOMER'},
              ],
            },
          },
      'POST /api/emails/preview': (_) =>
          {'from': null, 'to': [], 'unresolved': [], 'problems': []},
    };

Future<void> _pump(
  WidgetTester tester, {
  required _Backend backend,
  required CurrentUser user,
  required String location,
  required Map<String, Widget Function(GoRouterState state)> pages,
}) async {
  tester.view.physicalSize = const Size(1366, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: location,
    routes: [
      for (final e in pages.entries)
        GoRoute(path: e.key, builder: (_, s) => Scaffold(body: e.value(s))),
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
}

/// Lets the compose form's debounced preview go out and come back.
Future<void> _settleCompose(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 500));
  await tester.pumpAndSettle();
}

/// A button of kind [T] labelled [label]; `.icon` buttons are private subclasses, which a plain
/// type finder misses.
Finder _button<T>(String label) =>
    find.ancestor(of: find.text(label), matching: find.byWidgetPredicate((w) => w is T));

/// Closes the compose form without sending.
Future<void> _cancelCompose(WidgetTester tester) async {
  await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Cancel')));
  await tester.pumpAndSettle();
}

Map<String, dynamic>? _composeQuery(_Backend backend) {
  final asked = backend.sent('GET /api/emails/context');
  return asked.isEmpty ? null : asked.last.queryParameters;
}

/// What the compose form asks for a record just created, the local UTC offset included.
Map<String, dynamic> _createdQuery(String type, int id) => {
      'entityType': type,
      'entityId': id,
      'event': 'CREATED',
      'utcOffsetMinutes': DateTime.now().timeZoneOffset.inMinutes,
    };

void main() {
  group('customers', () {
    _Backend backend() => _Backend({
          'GET /api/table-schemas/customers': (_) => {'entity': 'customers', 'columns': []},
          'GET /api/customers': (_) => _page([_acme]),
          'GET /api/customers/summary': (_) => {'count': 1},
          'POST /api/customers': (_) => {..._acme, 'id': 17, 'name': 'Globex'},
          ..._composeRoutes('CUSTOMER', 'Customer Globex'),
        });

    Future<void> pumpList(WidgetTester tester, _Backend backend, Set<String> privileges) =>
        _pump(tester,
            backend: backend,
            user: _user(privileges),
            location: '/customers',
            pages: {
              '/customers': (_) =>
                  const CustomersScreen(query: TableQuery(sort: 'name,asc')),
            });

    testWidgets('a sender gets Send email on the page, on each row and for a selection',
        (tester) async {
      await pumpList(tester, backend(),
          {Privileges.customerView, Privileges.emailView, Privileges.emailSend});

      expect(_button<OutlinedButton>('Send email'), findsOneWidget);
      expect(find.byTooltip('Send email'), findsOneWidget);
      // Sending is the only thing this user can do with a selection, and it is enough for one.
      await tester.tap(find.byType(Checkbox).last);
      await tester.pumpAndSettle();
      expect(_button<TextButton>('Send email'), findsOneWidget);
    });

    testWidgets('someone who may not send gets none of it, and rows still open', (tester) async {
      await pumpList(tester, backend(), {Privileges.customerView, Privileges.emailView});

      expect(find.text('Send email'), findsNothing);
      expect(find.byTooltip('Send email'), findsNothing);
      expect(find.byType(Checkbox), findsNothing);
      expect(find.byTooltip('Open'), findsOneWidget);
    });

    testWidgets('a new customer saved with the box ticked opens the compose for it',
        (tester) async {
      final api = backend();
      await pumpList(tester, api, {
        Privileges.customerView,
        Privileges.customerManage,
        Privileges.emailView,
        Privileges.emailSend,
      });

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-pass');
      await tester.tap(find.text('Notify through email'));
      final listed = api.sent('GET /api/customers').length;
      await tester.tap(find.text('Save'));
      await _settleCompose(tester);

      expect(api.sent('POST /api/customers'), hasLength(1));
      expect(_composeQuery(api), _createdQuery('CUSTOMER', 17));
      expect(find.text('About Customer Globex'), findsOneWidget);
      // The list behind already shows the new customer.
      expect(api.sent('GET /api/customers').length, greaterThan(listed));

      await _cancelCompose(tester);
      expect(find.text('Send'), findsNothing);
    });

    testWidgets('the box is not offered without EMAIL_SEND, and nothing opens after saving',
        (tester) async {
      final api = backend();
      await pumpList(tester, api, {Privileges.customerView, Privileges.customerManage});

      await tester.tap(find.text('New customer'));
      await tester.pumpAndSettle();
      expect(find.text('Notify through email'), findsNothing);
      await tester.enterText(find.widgetWithText(TextFormField, 'Name'), 'Globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Username'), 'globex');
      await tester.enterText(find.widgetWithText(TextFormField, 'Password'), 'secret-pass');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(api.sent('POST /api/customers'), hasLength(1));
      expect(find.text('New customer'), findsOneWidget);
      expect(_composeQuery(api), isNull);
    });

    testWidgets('a customer login sees Send email and the Email tab on its own customer',
        (tester) async {
      final api = _Backend({
        'GET /api/customers/5': (_) => _acme,
        'GET /api/emails': (_) => _page([]),
      });
      await _pump(tester,
          backend: api,
          user: _user({Privileges.customerView, Privileges.emailView, Privileges.emailSend},
              customerId: 5),
          location: '/customers/5',
          pages: {'/customers/:id': (_) => const CustomerDetailScreen(id: 5)});

      expect(find.text('Acme Ltd'), findsWidgets);
      expect(find.widgetWithText(Tab, 'Email'), findsOneWidget);
      expect(find.text('No emails about this customer yet.'), findsOneWidget);
      final header = find.text('Send email').first;
      expect(tester.getTopLeft(header).dy,
          lessThan(tester.getTopLeft(find.byType(TabBar)).dy));
    });
  });

  group('invoices', () {
    testWidgets('the details page puts Email last among its tabs, with Send email in the header',
        (tester) async {
      final api = _Backend({
        'GET /api/invoices/42': (_) => {
              'id': 42,
              'invoiceNumber': 'INV-0042',
              'customerId': 5,
              'customerName': 'Acme Ltd',
              'invoiceDate': '2026-09-01',
              'total': 1200,
              'paidAmount': 0,
              'balance': 1200,
              'status': 'UNPAID',
              'items': [],
            },
        'GET /api/emails': (_) => _page([]),
      });
      await _pump(tester,
          backend: api,
          user: _user({
            Privileges.invoiceView,
            Privileges.promiseView,
            Privileges.auditView,
            Privileges.emailView,
            Privileges.emailSend,
          }),
          location: '/invoices/42',
          pages: {
            '/invoices/:id': (_) => const InvoiceDetailScreen(id: 42, initialTab: 'email'),
          });

      final tabs = tester.widgetList<Tab>(find.byType(Tab)).map((t) => t.text).toList();
      expect(tabs, ['Payment Promise', 'History', 'Email']);
      expect(api.sent('GET /api/emails').single.queryParameters,
          containsPair('entityType', 'INVOICE'));
      final header = find.text('Send email').first;
      expect(tester.getTopLeft(header).dy,
          lessThan(tester.getTopLeft(find.byType(TabBar)).dy));
    });

    testWidgets('a new invoice opens the compose before the form leaves for the list',
        (tester) async {
      final api = _Backend({
        'GET /api/pocs/my-scope': (_) =>
            {'userId': 3, 'sales': true, 'success': false, 'collection': false},
        'GET /api/pocs/assignable': (_) => [
              {'id': 3, 'username': 'jane', 'fullName': 'Jane Doe'},
            ],
        'GET /api/customers': (_) => _page([_acme]),
        'GET /api/products': (_) => _page([
              {'id': 8, 'name': 'Widget', 'price': 100},
            ]),
        'POST /api/invoices': (_) => {'id': 42, 'invoiceNumber': 'INV-0042'},
        ..._composeRoutes('INVOICE', 'Invoice INV-0042'),
      });
      await _pump(tester,
          backend: api,
          user: _user({
            Privileges.invoiceView,
            Privileges.invoiceManage,
            Privileges.pocView,
            Privileges.emailView,
            Privileges.emailSend,
          }),
          location: '/invoices/new',
          pages: {
            '/invoices/new': (_) => const InvoiceFormScreen(),
            '/invoices': (_) => const Text('invoices page'),
          });

      Future<void> pick(int field, String label) async {
        await tester.tap(find.text('Select…').at(field));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
      }

      await pick(0, 'Acme Ltd');
      await pick(0, 'Widget');
      await tester.tap(find.text('Notify through email'));
      await tester.tap(find.text('Create invoice'));
      await _settleCompose(tester);

      expect(api.sent('POST /api/invoices'), hasLength(1));
      expect(_composeQuery(api), _createdQuery('INVOICE', 42));
      expect(find.text('About Invoice INV-0042'), findsOneWidget);
      expect(find.text('invoices page'), findsNothing);

      await _cancelCompose(tester);
      expect(find.text('invoices page'), findsOneWidget);
    });

    testWidgets('Connect in the compose form after a new invoice leads to the Gmail page, not the list',
        (tester) async {
      final api = _Backend({
        'GET /api/pocs/my-scope': (_) =>
            {'userId': 3, 'sales': true, 'success': false, 'collection': false},
        'GET /api/pocs/assignable': (_) => [
              {'id': 3, 'username': 'jane', 'fullName': 'Jane Doe'},
            ],
        'GET /api/customers': (_) => _page([_acme]),
        'GET /api/products': (_) => _page([
              {'id': 8, 'name': 'Widget', 'price': 100},
            ]),
        'POST /api/invoices': (_) => {'id': 42, 'invoiceNumber': 'INV-0042'},
        ..._composeRoutes('INVOICE', 'Invoice INV-0042', selfGmail: 'NOT_CONNECTED'),
      });
      await _pump(tester,
          backend: api,
          user: _user({
            Privileges.invoiceView,
            Privileges.invoiceManage,
            Privileges.pocView,
            Privileges.emailView,
            Privileges.emailSend,
          }),
          location: '/invoices/new',
          pages: {
            '/invoices/new': (_) => const InvoiceFormScreen(),
            '/invoices': (_) => const Text('invoices page'),
            '/me/gmail': (_) => const Text('gmail page'),
          });

      Future<void> pick(int field, String label) async {
        await tester.tap(find.text('Select…').at(field));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
      }

      await pick(0, 'Acme Ltd');
      await pick(0, 'Widget');
      await tester.tap(find.text('Notify through email'));
      await tester.tap(find.text('Create invoice'));
      await _settleCompose(tester);
      expect(find.text('Connect your Gmail to send email'), findsOneWidget);

      // The suggestion filled the form, so leaving asks first.
      await tester.tap(find.widgetWithText(TextButton, 'Connect'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Leave'));
      await tester.pumpAndSettle();

      expect(find.text('gmail page'), findsOneWidget);
      expect(find.text('invoices page'), findsNothing);
      expect(api.sent('POST /api/invoices'), hasLength(1));
      expect(api.sent('POST /api/emails'), isEmpty);
    });
  });

  group('payments', () {
    _Backend backend() => _Backend({
          'GET /api/customers/5/pocs': (_) => [
                {
                  'id': 1,
                  'pocType': 'COLLECTION',
                  'primary': true,
                  'user': {'id': 12, 'username': 'bob', 'fullName': 'Bob Smith'},
                },
              ],
          'GET /api/invoices': (_) => _page([]),
          'GET /api/promises': (_) => _page([]),
          'POST /api/payments': (_) => {'id': 77},
          ..._composeRoutes('PAYMENT', 'Payment #77'),
        });

    /// Opens Record payment for Acme from a plain page, and hands back what the call resolved to
    /// (null while it has not).
    Future<bool? Function()> open(WidgetTester tester, _Backend api, Set<String> privileges) async {
      bool? result;
      await _pump(tester,
          backend: api,
          user: _user(privileges),
          location: '/',
          pages: {
            '/': (_) => Builder(
                  builder: (context) => TextButton(
                    onPressed: () async => result = await showRecordPaymentDialog(
                        context: context, customer: Customer.fromJson(_acme)),
                    child: const Text('open'),
                  ),
                ),
          });
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Amount *'), '250');
      return () => result;
    }

    testWidgets('with the box ticked the compose opens for the new payment once the dialog closes',
        (tester) async {
      final api = backend();
      final result = await open(tester, api,
          {Privileges.paymentView, Privileges.paymentManage, Privileges.emailSend});

      await tester.tap(find.text('Notify through email'));
      await tester.tap(find.text('Record'));
      await _settleCompose(tester);

      expect(api.sent('POST /api/payments'), hasLength(1));
      expect(find.text('Record payment'), findsNothing);
      expect(_composeQuery(api), _createdQuery('PAYMENT', 77));
      expect(find.text('About Payment #77'), findsOneWidget);
      // The caller hears back once the compose has closed, so it refreshes after that.
      expect(result(), isNull);

      await _cancelCompose(tester);
      expect(result(), isTrue);
    });

    testWidgets('without EMAIL_SEND there is no box, and recording resolves straight away',
        (tester) async {
      final api = backend();
      final result = await open(tester, api, {Privileges.paymentView, Privileges.paymentManage});

      expect(find.text('Notify through email'), findsNothing);
      await tester.tap(find.text('Record'));
      await tester.pumpAndSettle();

      expect(api.sent('POST /api/payments'), hasLength(1));
      expect(_composeQuery(api), isNull);
      expect(result(), isTrue);
    });

    testWidgets('cancelling records nothing and resolves false', (tester) async {
      final api = backend();
      final result = await open(tester, api,
          {Privileges.paymentView, Privileges.paymentManage, Privileges.emailSend});

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(api.sent('POST /api/payments'), isEmpty);
      expect(result(), isFalse);
    });

    testWidgets('the details page has neither the button nor the tab without the privileges',
        (tester) async {
      final api = _Backend({
        'GET /api/payments/77': (_) => {
              'id': 77,
              'customerId': 5,
              'customerName': 'Acme Ltd',
              'amount': 250,
              'paidAt': '2026-09-17T10:00:00Z',
              'status': 'ACTIVE',
              'invoices': [],
            },
      });
      await _pump(tester,
          backend: api,
          user: _user({Privileges.paymentView, Privileges.auditView}),
          location: '/payments/77',
          pages: {'/payments/:id': (_) => const PaymentDetailScreen(id: 77)});

      expect(find.text('Payment #77'), findsOneWidget);
      expect(find.widgetWithText(Tab, 'History'), findsOneWidget);
      expect(find.widgetWithText(Tab, 'Email'), findsNothing);
      expect(find.text('Send email'), findsNothing);
    });
  });
}
