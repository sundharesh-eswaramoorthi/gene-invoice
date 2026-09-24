import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';
import 'package:gene_invoice/core/theme.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/features/documents/document_actions.dart';
import 'package:gene_invoice/features/documents/document_models.dart';
import 'package:gene_invoice/features/documents/document_providers.dart';
import 'package:gene_invoice/features/documents/upload_document_dialog.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';
import 'package:gene_invoice/shared/widgets/detail_scaffold.dart';

import 'support/fake_backend.dart';

const _staff = {
  Privileges.documentView,
  Privileges.documentManage,
  Privileges.invoiceView,
  Privileges.invoiceManage,
};

const _viewer = {Privileges.documentView, Privileges.invoiceView};

CurrentUser _user(Set<String> privileges,
        {int? customerId, List<RegionGrant> regions = const []}) =>
    CurrentUser(
      id: 3,
      username: 'jane',
      fullName: 'Jane Doe',
      role: customerId == null ? 'CASHIER' : 'CUSTOMER',
      privileges: privileges,
      customerId: customerId,
      regions: regions,
    );

Map<String, dynamic> _document(
  int id, {
  String filename = 'terms.pdf',
  String contentType = 'application/pdf',
  String sizeLabel = '1.2 MB',
  String visibility = 'INTERNAL',
  String? description,
  bool canDownload = true,
  bool canEdit = true,
  bool canDelete = true,
}) =>
    {
      'id': id,
      'entityType': 'INVOICE',
      'entityId': 42,
      'entityLabel': 'Invoice INV-0042',
      'entityLink': '/invoices/42',
      'filename': filename,
      'contentType': contentType,
      'sizeBytes': 1258291,
      'sizeLabel': sizeLabel,
      'visibility': visibility,
      'description': description,
      'uploadedBy': {'userId': 3, 'name': 'Jane Doe'},
      'uploadedAt': '2026-09-18T10:15:00Z',
      'canDownload': canDownload,
      'canEdit': canEdit,
      'canDelete': canDelete,
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

PickedDocument _file(String name, {String? mimeType, int bytes = 8}) => PickedDocument(
      name: name,
      mimeType: mimeType,
      bytes: Uint8List(bytes),
    );

Future<List<String>> _pump(
  WidgetTester tester,
  FakeBackend backend, {
  required CurrentUser user,
  DocumentSaver? saver,
  String initialTab = 'documents',
  bool settle = true,
  int? regionId,
  Size size = const Size(1366, 1400),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final slugs = <String>[];
  await tester.pumpWidget(ProviderScope(
    overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(user),
      if (saver != null) documentSaverProvider.overrideWithValue(saver),
    ],
    child: MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Consumer(builder: (context, ref, _) {
          final tab = documentsDetailTab(ref,
              type: DocumentEntityType.invoice,
              entityId: 42,
              entityLabel: 'INV-0042',
              regionId: regionId);
          return DetailScaffold(
            title: 'INV-0042',
            top: const SizedBox(height: 40),
            initialTabSlug: initialTab,
            onTabChanged: slugs.add,
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
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));
  }
  return slugs;
}

FakeBackend _backend({
  List<Map<String, dynamic>>? documents,
  int? count,
  Object? Function(RequestOptions)? list,
}) =>
    FakeBackend({
      'GET /api/documents': list ?? (_) => _page(documents ?? const []),
      'GET /api/documents/count': (_) => {'count': count ?? (documents?.length ?? 0)},
    });

void main() {
  testWidgets('a record lists what is attached to it, and who put each one there', (tester) async {
    final backend = _backend(documents: [
      _document(1, description: 'Signed terms'),
      _document(2,
          filename: 'photo.jpg',
          contentType: 'image/jpeg',
          sizeLabel: '240 KB',
          visibility: 'SHARED'),
    ]);
    await _pump(tester, backend, user: _user(_staff));

    expect(backend.sent('GET /api/documents').single.queryParameters,
        {'entityType': 'INVOICE', 'entityId': 42, 'page': 0, 'size': 20});
    expect(find.text('Documents (2)'), findsOneWidget);

    final when = formatDateTime('2026-09-18T10:15:00Z');
    expect(find.text('terms.pdf'), findsOneWidget);
    expect(find.text('PDF · 1.2 MB · Jane Doe · $when'), findsOneWidget);
    expect(find.text('Signed terms'), findsOneWidget);
    expect(find.text('Internal'), findsOneWidget);
    expect(find.text('photo.jpg'), findsOneWidget);
    expect(find.text('JPEG · 240 KB · Jane Doe · $when'), findsOneWidget);
    expect(find.text('Shared'), findsOneWidget);
    expect(find.byTooltip('Download'), findsNWidgets(2));
    expect(find.byTooltip('Edit'), findsNWidgets(2));
    expect(find.byTooltip('Remove'), findsNWidgets(2));
  });

  testWidgets('older documents load a page at a time, below the newer ones (AC-C2)',
      (tester) async {
    final backend = FakeBackend({
      'GET /api/documents': (options) => (options.queryParameters['page'] as int? ?? 0) == 0
          ? _page([for (var i = 1; i <= 20; i++) _document(i, filename: 'file-$i.pdf')],
              total: 21)
          : _page([_document(21, filename: 'oldest.pdf')], page: 1, total: 21),
      'GET /api/documents/count': (_) => {'count': 21},
    });
    await _pump(tester, backend, user: _user(_staff));

    await tester.scrollUntilVisible(find.text('Show older documents (1 more)'), 400,
        scrollable: _list);
    await tester.tap(find.text('Show older documents (1 more)'));
    await tester.pumpAndSettle();

    expect(backend.sent('GET /api/documents').map((r) => r.queryParameters['page']), [0, 1]);
    expect(find.textContaining('Show older documents'), findsNothing);
    await tester.scrollUntilVisible(find.text('oldest.pdf'), 400, scrollable: _list);
    expect(find.text('oldest.pdf'), findsOneWidget);
  });

  testWidgets('the tab says how many there are before it is opened (AC-C1)', (tester) async {
    final backend = _backend(documents: [_document(1)], count: 3);
    await _pump(tester, backend, user: _user(_staff), initialTab: 'history');

    expect(backend.sent('GET /api/documents/count').single.queryParameters,
        {'entityType': 'INVOICE', 'entityId': 42});
    expect(backend.sent('GET /api/documents'), isEmpty);
    expect(find.descendant(of: find.byType(Badge), matching: find.text('3')), findsOneWidget);
  });

  testWidgets('an empty record says so, and no badge counts nothing (AC-C21)', (tester) async {
    await _pump(tester, _backend(documents: const []), user: _user(_staff));

    expect(find.text('Documents (0)'), findsOneWidget);
    expect(find.text('No documents on this invoice yet.'), findsOneWidget);
    expect(find.text('Upload one to keep it with this invoice.'), findsOneWidget);
    expect(find.byType(Badge), findsNothing);

    await tester.tap(find.text('Upload'));
    await tester.pumpAndSettle();
    expect(find.text('Choose file'), findsNothing);
    expect(find.text('Open the app in a browser to attach a file.'), findsOneWidget);
    expect(find.text('PDF, PNG, JPEG, Word or Excel, up to 10 MB.'), findsOneWidget);
  });

  testWidgets('a slow load shows it is loading, not an empty record (AC-C21)', (tester) async {
    final answer = Completer<void>();
    final backend = _backend(documents: [_document(1)])
      ..held['GET /api/documents'] = answer.future;
    await _pump(tester, backend, user: _user(_staff), settle: false);

    expect(find.text('Documents'), findsWidgets);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(find.textContaining('No documents'), findsNothing);

    answer.complete();
    await tester.pumpAndSettle();
    expect(find.text('terms.pdf'), findsOneWidget);
  });

  testWidgets('a failed load says why and offers another go (AC-C21)', (tester) async {
    var failing = true;
    final backend = _backend(list: (_) =>
        failing ? const FakeFailure(500, {'message': 'Boom'}) : _page([_document(1)]));
    await _pump(tester, backend, user: _user(_staff));

    expect(find.text('Documents unavailable: Boom'), findsOneWidget);
    expect(find.textContaining('No documents'), findsNothing);

    failing = false;
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(backend.sent('GET /api/documents'), hasLength(2));
    expect(find.text('terms.pdf'), findsOneWidget);
  });

  testWidgets('opening the tab puts it in the URL, so a document view is linkable (AC-C19)',
      (tester) async {
    final slugs = await _pump(tester, _backend(documents: [_document(1)]),
        user: _user(_staff), initialTab: 'history');

    await tester.tap(find.text('Documents'));
    await tester.pumpAndSettle();
    expect(slugs, ['documents']);
  });

  group('privileges (AC-C22)', () {
    testWidgets('without DOCUMENT_VIEW there is no Documents tab', (tester) async {
      final backend = _backend(documents: [_document(1)]);
      await _pump(tester, backend, user: _user({Privileges.invoiceView}));

      expect(find.text('Documents'), findsNothing);
      expect(find.text('History'), findsOneWidget);
      expect(backend.requests, isEmpty);
    });

    testWidgets('a viewer sees the documents but nothing that would 403', (tester) async {
      await _pump(
        tester,
        _backend(documents: [_document(1, canEdit: false, canDelete: false)]),
        user: _user(_viewer),
      );

      expect(find.text('terms.pdf'), findsOneWidget);
      expect(find.byTooltip('Download'), findsOneWidget);
      expect(find.text('Upload'), findsNothing);
      expect(find.byTooltip('Edit'), findsNothing);
      expect(find.byTooltip('Remove'), findsNothing);
    });

    testWidgets('DOCUMENT_MANAGE without the invoice\'s own leaves nothing to upload with',
        (tester) async {
      await _pump(
        tester,
        _backend(documents: const []),
        user: _user({
          Privileges.documentView,
          Privileges.documentManage,
          Privileges.invoiceView,
        }),
      );

      expect(find.text('Upload'), findsNothing);
    });

    testWidgets('a branch you only read offers nothing to attach with', (tester) async {
      await _pump(
        tester,
        _backend(documents: [_document(1, canEdit: false, canDelete: false)]),
        user: _user(_staff, regions: const [
          RegionGrant(id: 3, code: 'NORTH', name: 'North', rights: {regionRightManage}),
          RegionGrant(id: 7, code: 'WEST', name: 'West', rights: {regionRightView}),
        ]),
        regionId: 7,
      );

      // DOCUMENT_MANAGE is MANAGE-level in the region partition, so holding it in NORTH is not
      // permission to attach a file to a West record — and the server now asks per record (B1).
      expect(find.text('terms.pdf'), findsOneWidget);
      expect(find.text('Upload'), findsNothing);
    });

    testWidgets('the same person may attach in the branch they manage', (tester) async {
      await _pump(
        tester,
        _backend(documents: const []),
        user: _user(_staff, regions: const [
          RegionGrant(id: 3, code: 'NORTH', name: 'North', rights: {regionRightManage}),
          RegionGrant(id: 7, code: 'WEST', name: 'West', rights: {regionRightView}),
        ]),
        regionId: 3,
      );

      expect(find.text('Upload'), findsOneWidget);
    });

    testWidgets('a customer login may attach to its own record (§1, answer 4)', (tester) async {
      await _pump(
        tester,
        _backend(documents: const []),
        user: _user(
            {Privileges.documentView, Privileges.documentManage, Privileges.invoiceView},
            customerId: 5),
      );

      expect(find.text('Upload'), findsOneWidget);
    });
  });

  group('uploading', () {
    Future<List<RequestOptions>> pumpForm(
      WidgetTester tester, {
      required CurrentUser user,
      required PickedDocument file,
      FakeBackend? backend,
    }) async {
      tester.view.physicalSize = const Size(1000, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      final server = backend ??
          FakeBackend({'POST /api/documents': (_) => _document(9, filename: file.name)});
      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(server.dio),
          currentUserProvider.overrideWithValue(user),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => openUploadDocumentDialog(context,
                    type: DocumentEntityType.invoice,
                    entityId: 42,
                    entityLabel: 'INV-0042',
                    file: file),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      return server.requests;
    }

    testWidgets('a file bigger than the limit is refused before it is sent (AC-C20)',
        (tester) async {
      final requests = await pumpForm(tester,
          user: _user(_staff), file: _file('huge.pdf', bytes: 10485761));

      expect(find.text('huge.pdf'), findsOneWidget);
      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      expect(find.text('The file is larger than 10 MB'), findsOneWidget);
      expect(requests, isEmpty);
    });

    testWidgets('a file about to be refused does not read as the limit it is over (DOC-8)',
        (tester) async {
      await pumpForm(tester, user: _user(_staff), file: _file('huge.pdf', bytes: 10485761));
      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      expect(find.text('The file is larger than 10 MB'), findsOneWidget);
      expect(find.text('10 MB'), findsNothing);
      expect(find.text('10.1 MB'), findsOneWidget);
    });

    testWidgets('taking the file off the form takes its refusal with it (UI-05)', (tester) async {
      final requests = await pumpForm(tester,
          user: _user(_staff), file: _file('huge.pdf', bytes: 10485761));
      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();
      expect(find.text('The file is larger than 10 MB'), findsOneWidget);

      expect(find.byTooltip('Choose another file'), findsNothing);
      await tester.tap(find.byTooltip('Remove file'));
      await tester.pumpAndSettle();

      expect(find.text('huge.pdf'), findsNothing);
      expect(find.text('PDF, PNG, JPEG, Word or Excel, up to 10 MB.'), findsOneWidget);
      expect(find.text('The file is larger than 10 MB'), findsNothing);
      expect(requests, isEmpty);
    });

    testWidgets('a kind of file the server does not keep is refused before it is sent',
        (tester) async {
      final requests = await pumpForm(tester,
          user: _user(_staff), file: _file('notes.txt', mimeType: 'text/plain'));

      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      expect(
          find.text('Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)'),
          findsOneWidget);
      expect(requests, isEmpty);
    });

    testWidgets('a refusal from the server leaves the form open to try again (AC-C21)',
        (tester) async {
      final backend = FakeBackend({
        'POST /api/documents': (_) => const FakeFailure(400, {
              'fieldErrors': {'file': 'The file is larger than 10 MB'},
            }),
      });
      await pumpForm(tester,
          user: _user(_staff), file: _file('terms.pdf'), backend: backend);

      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      // Word for word what the client's own size check says, so an over-large upload reads
      // identically whichever of the two caught it (AC-C9); the field name used to be glued in
      // front of the server's sentence (UI-03).
      expect(find.text('The file is larger than 10 MB'), findsOneWidget);
      expect(find.text('Upload'), findsOneWidget);
      expect(find.text('terms.pdf'), findsOneWidget);
    });

    testWidgets('a customer login is not asked who may see its upload (D7)', (tester) async {
      await pumpForm(tester,
          user: _user({Privileges.documentView, Privileges.documentManage}, customerId: 5),
          file: _file('terms.pdf'));

      expect(find.text('Who can see it'), findsNothing);
      expect(find.text('Internal only'), findsNothing);
    });

    testWidgets('staff say who may see it, and it goes with the file', (tester) async {
      final requests =
          await pumpForm(tester, user: _user(_staff), file: _file('terms.pdf'));

      expect(find.text('Who can see it'), findsOneWidget);
      await tester.enterText(find.byType(TextField), 'Signed terms');
      await tester.tap(find.text('Internal only'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Shared with customer').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      final form = requests.single.data as FormData;
      expect(Map.fromEntries(form.fields), {
        'entityType': 'INVOICE',
        'entityId': '42',
        'description': 'Signed terms',
        'visibility': 'SHARED',
      });
      expect(form.files.single.key, 'file');
      expect(form.files.single.value.filename, 'terms.pdf');
    });

    testWidgets('the form shows how far along the upload is, then the tab says it is there',
        (tester) async {
      tester.view.physicalSize = const Size(1366, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      final answer = Completer<void>();
      final requests = <RequestOptions>[];
      final dio = Dio()
        ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
          requests.add(options);
          if (options.method == 'POST') {
            options.onSendProgress?.call(512, 1024);
            await answer.future;
          }
          handler.resolve(Response(
            requestOptions: options,
            statusCode: 200,
            data: switch (options.path) {
              '/api/documents/count' => const {'count': 1},
              '/api/documents' when options.method == 'POST' => _document(9),
              _ => _page(requests.any((r) => r.method == 'POST') ? [_document(9)] : const []),
            },
          ));
        }));

      await tester.pumpWidget(ProviderScope(
        overrides: [
          dioProvider.overrideWithValue(dio),
          currentUserProvider.overrideWithValue(_user(_staff)),
          documentPickerProvider.overrideWithValue(() async => _file('terms.pdf')),
        ],
        child: MaterialApp(
          theme: AppTheme.light(),
          home: Scaffold(
            body: Consumer(builder: (context, ref, _) {
              final tab = documentsDetailTab(ref,
                  type: DocumentEntityType.invoice, entityId: 42, entityLabel: 'INV-0042');
              return DetailScaffold(
                title: 'INV-0042',
                top: const SizedBox(height: 40),
                initialTabSlug: 'documents',
                tabs: [if (tab != null) tab],
              );
            }),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Upload'));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'Upload'));
      await tester.pumpAndSettle();
      expect(find.text('Choose a file'), findsOneWidget);
      expect(requests.where((r) => r.method == 'POST'), isEmpty);

      await tester.tap(find.text('Choose file'));
      await tester.pumpAndSettle();
      expect(find.text('terms.pdf'), findsOneWidget);

      await tester.tap(find.widgetWithText(FilledButton, 'Upload'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 1));
      await tester.pump(const Duration(milliseconds: 1));

      expect(find.text('Uploading… 50%'), findsOneWidget);
      expect(tester.widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator)).value,
          0.5);

      answer.complete();
      await tester.pumpAndSettle();
      expect(find.text('Document uploaded'), findsOneWidget);
      expect(requests.where((r) => r.path == '/api/documents' && r.method == 'GET'), hasLength(2));
      expect(requests.where((r) => r.path == '/api/documents/count'), hasLength(2));
      expect(find.text('terms.pdf'), findsOneWidget);
    });
  });

  testWidgets('downloading hands the file to the browser, named as it was uploaded',
      (tester) async {
    DownloadedDocument? saved;
    final backend = _backend(documents: [_document(1)]);
    backend.routes['GET /api/documents/1/download'] = (_) => [1, 2, 3];
    await _pump(tester, backend, user: _user(_staff), saver: (file) async {
      saved = file;
      return true;
    });

    await tester.tap(find.byTooltip('Download'));
    await tester.pumpAndSettle();

    expect(backend.sent('GET /api/documents/1/download').single.responseType,
        ResponseType.bytes);
    expect(saved?.filename, 'terms.pdf');
    expect(saved?.contentType, 'application/pdf');
    expect(saved?.bytes, [1, 2, 3]);
    expect(find.text('Downloaded terms.pdf'), findsOneWidget);
  });

  testWidgets('a download that fails says what the server said', (tester) async {
    final backend = _backend(documents: [_document(1)]);
    backend.routes['GET /api/documents/1/download'] = (_) => FakeFailure(
        404, Uint8List.fromList(utf8.encode('{"message":"Document not found"}')));
    await _pump(tester, backend, user: _user(_staff));

    await tester.tap(find.byTooltip('Download'));
    await tester.pumpAndSettle();

    expect(find.text('Document not found'), findsOneWidget);
  });

  testWidgets('removing a document asks first, then says it is gone (AC-C3)', (tester) async {
    final backend = _backend(documents: [_document(1)]);
    backend.routes['DELETE /api/documents/1'] = (_) => null;
    await _pump(tester, backend, user: _user(_staff));

    await tester.tap(find.byTooltip('Remove'));
    await tester.pumpAndSettle();
    expect(find.textContaining('terms.pdf stops being downloadable'), findsOneWidget);

    await tester.tap(find.text('Keep it'));
    await tester.pumpAndSettle();
    expect(backend.sent('DELETE /api/documents/1'), isEmpty);

    await tester.tap(find.byTooltip('Remove'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Remove'));
    await tester.pumpAndSettle();

    expect(backend.sent('DELETE /api/documents/1'), hasLength(1));
    expect(find.text('Document removed'), findsOneWidget);
    expect(backend.sent('GET /api/documents'), hasLength(2));
    expect(backend.sent('GET /api/documents/count'), hasLength(2));
  });

  testWidgets('editing changes what a document says and who may see it', (tester) async {
    final backend = _backend(documents: [_document(1, description: 'Signed terms')]);
    backend.routes['PATCH /api/documents/1'] =
        (_) => _document(1, description: 'Countersigned', visibility: 'SHARED');
    await _pump(tester, backend, user: _user(_staff));

    await tester.tap(find.byTooltip('Edit'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Countersigned');
    await tester.tap(find.text('Internal only'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Shared with customer').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Save'));
    await tester.pumpAndSettle();

    expect(backend.sent('PATCH /api/documents/1').single.data,
        {'description': 'Countersigned', 'visibility': 'SHARED'});
    expect(find.text('Document updated'), findsOneWidget);
  });

  group('what the form refuses before sending (§4.2)', () {
    test('the server\'s own words, so the answer does not change on the way', () {
      expect(documentRefusal(null), 'Choose a file');
      expect(documentRefusal(_file('huge.pdf', bytes: 10485761)),
          'The file is larger than 10 MB');
      expect(documentRefusal(_file('notes.txt', mimeType: 'text/plain')),
          'Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)');
      expect(documentRefusal(_file('big.pdf', bytes: 10485760)), isNull);
      for (final name in ['a.pdf', 'a.PNG', 'a.jpg', 'a.jpeg', 'a.docx', 'a.xlsx']) {
        expect(documentRefusal(_file(name)), isNull, reason: name);
      }
      expect(documentRefusal(_file('scan', mimeType: 'image/png')), isNull);
    });

    test('a size reads the way the server writes it, and never as less than it is (DOC-8)', () {
      expect(formatBytes(0), '0 B');
      expect(formatBytes(512), '512 B');
      expect(formatBytes(1023), '1023 B');
      expect(formatBytes(1024), '1 KB');
      expect(formatBytes(1536), '1.5 KB');
      expect(formatBytes(10485760), '10 MB');
      expect(formatBytes(10485761), '10.1 MB');
      expect(formatBytes(1048575), '1 MB');
      expect(formatBytes(1048576), '1 MB');
    });
  });
}

final _list = find.descendant(of: find.byType(ListView), matching: find.byType(Scrollable)).first;
