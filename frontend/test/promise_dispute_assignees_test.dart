import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/disputes/dispute_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_detail_screen.dart';
import 'package:gene_invoice/features/promises/promise_form_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/models/promise.dart';
import 'package:gene_invoice/shared/widgets/assignee_picker_field.dart';
import 'package:go_router/go_router.dart';

import 'support/fake_backend.dart';

// A promise and a dispute both take several assignees, picked with the very field the email To
// line and the task form use (A1). These are the two screens where that is done: the promise's
// own form, and the dispute's page — a dispute is raised from the customer's side, where there is
// no staff to pick, so the server makes assigning its own act (PATCH …/assignees, DISPUTE_MANAGE).
//
// What every one of these pins in the end is the masking: a customer login is sent no assignees
// at all, because who internally owns a piece of work is staff identity (AC-A8), and an empty
// list from that reader must never be shown as "nobody is on it".

const _email = {Privileges.emailView, Privileges.emailSend};

CurrentUser _staff(Set<String> privileges) => CurrentUser(
      id: 1,
      username: 'admin',
      fullName: 'Ada Admin',
      role: 'ADMIN',
      privileges: privileges,
      customerId: null,
    );

CurrentUser _customerLogin(Set<String> privileges) => CurrentUser(
      id: 30,
      username: 'acme',
      fullName: 'Acme Buyer',
      role: 'CUSTOMER',
      privileges: privileges,
      customerId: 5,
    );

/// One assignee as the server resolves it (`AssigneeDto`).
Map<String, dynamic> _person(int userId, String name) => {
      'kind': 'USER',
      'userId': userId,
      'label': name,
      'resolved': true,
      'people': [
        {'userId': userId, 'name': name, 'address': '${name.split(' ').first.toLowerCase()}@co.com'}
      ],
    };

/// A seat, and whether anybody is in it right now.
Map<String, dynamic> _seat({bool held = true}) => {
      'kind': 'ROLE',
      'role': 'COLLECTION_POC',
      'level': 'CUSTOMER',
      'label': 'Collection POC',
      'resolved': held,
      'people': held
          ? [
              {'userId': 12, 'name': 'Bob Smith', 'address': 'bob@co.com'}
            ]
          : <Map<String, dynamic>>[],
    };

Map<String, dynamic> _promise({List<Map<String, dynamic>> assignees = const []}) => {
      'id': 77,
      'customerId': 5,
      'customerName': 'Acme Ltd',
      'amount': 1200,
      'fulfilledAmount': 0,
      'remainingAmount': 1200,
      'promisedDate': '2026-09-30',
      'status': 'OPEN',
      'statusOverridden': false,
      'collectionPoc': {'id': 12, 'username': 'bob', 'fullName': 'Bob Smith'},
      'assignees': assignees,
      'notes': '',
      'invoices': [],
      'payments': [],
    };

Map<String, dynamic> _dispute({List<Map<String, dynamic>> assignees = const []}) => {
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
      'proposedChangeJson': null,
      'status': 'PENDING',
      'adminNotes': null,
      'assignees': assignees,
      'resolvedByUserId': null,
      'resolvedAt': null,
      'createdAt': '2026-09-15T09:00:00Z',
      'updatedAt': null,
    };

/// The compose context, which is also where the assignee picker's seats come from (A4): one
/// customer-level seat, held by Bob.
Map<String, dynamic> _context(String type, int? id) => {
      'entityType': type,
      'entityId': id,
      'delivery': {'configured': true},
      'sender': {
        'restricted': false,
        'self': {'userId': 1, 'name': 'Ada Admin', 'email': 'ada@co.com'},
      },
      'roles': [
        {
          'role': 'COLLECTION_POC',
          'label': 'Collection POC',
          'level': 'CUSTOMER',
          'levelLabel': 'Customer',
          'groupLabel': 'Customer level',
          'resolved': true,
          'people': [
            {'userId': 12, 'name': 'Bob Smith', 'email': 'bob@co.com'}
          ],
        },
      ],
      'customerEmails': {'available': true, 'addresses': <Map<String, dynamic>>[]},
    };

FakeBackend _backend({Map<String, Object? Function(dynamic)>? extra}) => FakeBackend({
      'GET /api/customers/5/pocs': (_) => [],
      'GET /api/pocs/assignable': (_) => [],
      'GET /api/invoices': (_) =>
          {'content': [], 'page': 0, 'size': 20, 'totalElements': 0, 'totalPages': 0},
      'GET /api/emails/context': (r) => _context(
          '${r.queryParameters['entityType']}', (r.queryParameters['entityId'] as num?)?.toInt()),
      'POST /api/promises': (_) => _promise(),
      'PUT /api/promises/77': (_) => _promise(),
      'GET /api/promises/77': (_) => _promise(),
      'GET /api/disputes/12': (_) => _dispute(),
      'PATCH /api/disputes/12/assignees': (_) => _dispute(),
      ...?extra,
    });

/// The routes these two pages move between; nothing here navigates unless a test presses
/// something that does.
Future<void> _pump(
  WidgetTester tester, {
  required FakeBackend backend,
  required CurrentUser user,
  required String location,
  Widget Function(BuildContext context)? home,
  Size size = const Size(1366, 900),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final router = GoRouter(
    initialLocation: location,
    routes: [
      GoRoute(path: '/home', builder: (context, _) => Scaffold(body: home!(context))),
      GoRoute(path: '/promises', builder: (_, __) => const Text('promises list')),
      GoRoute(
        path: '/promises/:id',
        // The app shell gives every page its Scaffold; these pages have none of their own.
        builder: (_, s) => Scaffold(
            body: PromiseDetailScreen(id: int.parse(s.pathParameters['id']!))),
      ),
      GoRoute(path: '/disputes', builder: (_, __) => const Text('disputes list')),
      GoRoute(
        path: '/disputes/:id',
        builder: (_, s) => Scaffold(
            body: DisputeDetailScreen(id: int.parse(s.pathParameters['id']!))),
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
}

/// Opens the promise form over a blank page, as every caller of it does.
Future<void> _openPromiseForm(
  WidgetTester tester, {
  required FakeBackend backend,
  required CurrentUser user,
  PaymentPromise? existing,
}) async {
  await _pump(
    tester,
    backend: backend,
    user: user,
    location: '/home',
    home: (context) => Center(
      child: TextButton(
        onPressed: () => showPromiseDialog(
          context: context,
          customerId: 5,
          customerName: 'Acme Ltd',
          existing: existing,
        ),
        child: const Text('open'),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

List<Object?> _assigneesSent(FakeBackend backend, String key) =>
    ((backend.sent(key).single.data as Map)['assignees'] as List).toList();

void main() {
  group('the promise form', () {
    testWidgets('a new promise says it falls back to the Collection POC, and sends who was picked',
        (tester) async {
      final backend = _backend();
      await _openPromiseForm(tester, backend: backend, user: _staff({
        Privileges.promiseManage,
        Privileges.pocView,
        ..._email,
      }));

      // Empty is not "nobody" on a promise being raised: the server seeds the list from the
      // Collection POC (A6), and the box says so rather than leaving it to be discovered.
      expect(find.text('The Collection POC, unless you add people or roles below'), findsOneWidget);
      // The seats come from the promise people-book, under the heading the compose form uses.
      expect(find.text('Customer level'), findsOneWidget);
      await tester.tap(find.widgetWithText(ActionChip, 'Collection POC · Bob Smith <bob@co.com>'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).first, '1200');
      await tester.tap(find.text('Raise promise'));
      await tester.pumpAndSettle();

      expect(_assigneesSent(backend, 'POST /api/promises'), [
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
      ]);
    });

    testWidgets('an edit opens on who is already on it and hands the same list back',
        (tester) async {
      final backend = _backend();
      await _openPromiseForm(
        tester,
        backend: backend,
        user: _staff({Privileges.promiseManage, Privileges.pocView, ..._email}),
        existing: PaymentPromise.fromJson(
            _promise(assignees: [_person(9, 'Anil Kumar'), _seat(held: false)])),
      );

      // The names come with the promise: the field never picked them and cannot name them itself.
      expect(find.widgetWithText(InputChip, 'Anil Kumar'), findsOneWidget);
      expect(find.widgetWithText(InputChip, 'Collection POC · Bob Smith <bob@co.com>'),
          findsOneWidget);
      // Empty means nobody on an edit, not the POC, and the box says the other thing here.
      expect(find.text('Nobody — add people or roles below'), findsNothing);

      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      expect(_assigneesSent(backend, 'PUT /api/promises/77'), [
        {'type': 'USER', 'userId': 9},
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
      ]);
    });

    testWidgets('a caller who may not pick people is given no picker, and assigns nothing',
        (tester) async {
      final backend = _backend();
      // No EMAIL_SEND: the roles and the people search are both email endpoints, and a picker
      // that could only 403 is worse than none.
      await _openPromiseForm(tester,
          backend: backend,
          user: _staff({Privileges.promiseManage, Privileges.pocView}),
          existing: PaymentPromise.fromJson(_promise(assignees: [_person(9, 'Anil Kumar')])));

      expect(find.byType(AssigneePickerField), findsNothing);
      expect(backend.sent('GET /api/emails/context'), isEmpty);

      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();

      // Left out of the request entirely: a null list leaves the assignees alone, where an empty
      // one would quietly unassign Anil (A7).
      final body = backend.sent('PUT /api/promises/77').single.data as Map;
      expect(body.containsKey('assignees'), isFalse);
    });
  });

  group("a promise's own page", () {
    testWidgets('names everyone on it, and says when a seat reaches nobody', (tester) async {
      await _pump(
        tester,
        backend: _backend(extra: {
          'GET /api/promises/77': (_) =>
              _promise(assignees: [_person(9, 'Anil Kumar'), _seat(held: false)]),
        }),
        user: _staff({Privileges.promiseView, Privileges.pocView}),
        location: '/promises/77',
      );

      expect(find.text('Assigned to'), findsOneWidget);
      expect(find.text('Anil Kumar'), findsOneWidget);
      // The thing worth noticing on this page: it looks assigned and reaches nobody.
      expect(find.text('Collection POC · nobody assigned'), findsOneWidget);
    });

    testWidgets('a promise nobody is answerable for says so', (tester) async {
      await _pump(
        tester,
        backend: _backend(),
        user: _staff({Privileges.promiseView, Privileges.pocView}),
        location: '/promises/77',
      );
      expect(find.text('Unassigned'), findsOneWidget);
    });

    testWidgets('a customer login is shown no such row at all (AC-A8)', (tester) async {
      await _pump(
        tester,
        backend: _backend(),
        user: _customerLogin({Privileges.promiseView}),
        location: '/promises/77',
      );

      // The server sends a customer login an empty list because it may not learn staff identity.
      // Showing "Unassigned" would read as a fact about the promise rather than about the reader.
      expect(find.text('Assigned to'), findsNothing);
      expect(find.text('Unassigned'), findsNothing);
    });
  });

  group("a dispute's own page", () {
    const manager = {
      Privileges.disputeView,
      Privileges.disputeManage,
      ..._email,
    };

    testWidgets('names everyone on it', (tester) async {
      await _pump(
        tester,
        backend: _backend(extra: {
          'GET /api/disputes/12': (_) => _dispute(assignees: [_person(9, 'Anil Kumar'), _seat()]),
        }),
        user: _staff(manager),
        location: '/disputes/12',
      );

      expect(find.text('Assigned to'), findsOneWidget);
      expect(find.text('Anil Kumar'), findsOneWidget);
      expect(find.text('Collection POC · Bob Smith'), findsOneWidget);
      // Already assigned, so the button hands it on rather than picking it up.
      expect(find.text('Reassign'), findsOneWidget);
    });

    testWidgets('DISPUTE_MANAGE picks it up through the assignees endpoint', (tester) async {
      final backend = _backend();
      await _pump(
        tester,
        backend: backend,
        user: _staff(manager),
        location: '/disputes/12',
      );

      expect(find.text('Unassigned'), findsOneWidget);
      await tester.tap(find.text('Assign'));
      await tester.pumpAndSettle();

      expect(find.text('Assign this dispute'), findsOneWidget);
      // The dispute's own seats, resolved against it.
      await tester.tap(find.widgetWithText(ActionChip, 'Collection POC · Bob Smith <bob@co.com>'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();

      expect(_assigneesSent(backend, 'PATCH /api/disputes/12/assignees'), [
        {'type': 'ROLE', 'role': 'COLLECTION_POC', 'level': 'CUSTOMER'},
      ]);
      // The page it changed asks again, so the row behind the dialog is the saved one.
      expect(backend.sent('GET /api/disputes/12'), hasLength(2));
    });

    testWidgets('a refusal from the server is said, and the dialog stays open', (tester) async {
      final backend = _backend(extra: {
        'PATCH /api/disputes/12/assignees': (_) =>
            const FakeFailure(400, {'message': 'A dispute takes at most 10 assignees'}),
      });
      await _pump(tester, backend: backend, user: _staff(manager), location: '/disputes/12');

      await tester.tap(find.text('Assign'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.pumpAndSettle();

      expect(find.text('A dispute takes at most 10 assignees'), findsOneWidget);
      expect(find.text('Assign this dispute'), findsOneWidget);
    });

    testWidgets('without DISPUTE_MANAGE there is nothing to press', (tester) async {
      await _pump(
        tester,
        backend: _backend(extra: {
          'GET /api/disputes/12': (_) => _dispute(assignees: [_person(9, 'Anil Kumar')]),
        }),
        user: _staff({Privileges.disputeView, ..._email}),
        location: '/disputes/12',
      );

      expect(find.text('Anil Kumar'), findsOneWidget);
      expect(find.text('Assign'), findsNothing);
      expect(find.text('Reassign'), findsNothing);
    });

    testWidgets('a customer login sees neither the row nor the button (AC-A8)', (tester) async {
      await _pump(
        tester,
        backend: _backend(),
        user: _customerLogin({Privileges.disputeView, Privileges.disputeCreate}),
        location: '/disputes/12',
      );

      expect(find.text('Assigned to'), findsNothing);
      expect(find.text('Unassigned'), findsNothing);
      expect(find.text('Assign'), findsNothing);
    });
  });
}
