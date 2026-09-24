import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/region/region_providers.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

import 'support/fake_backend.dart';

/// The server refuses any page size outside TableQuery.ALLOWED_SIZES with
/// 400 "size must be one of [10, 20, 50]". The branch map used to ask for 200 in one go, so it
/// never loaded: the grant editor and the customer branch picker both showed only
/// "Could not load the branches", and a company with more than one branch could not be
/// administered from the UI at all. No test covered either screen, which is why it shipped (B1).
void main() {
  const allowed = [10, 20, 50];

  Map<String, dynamic> page(List<Map<String, dynamic>> rows, int p, int totalPages) => {
        'content': rows,
        'page': p,
        'size': 50,
        'totalElements': totalPages * 50,
        'totalPages': totalPages,
      };

  Map<String, dynamic> region(int id) =>
      {'id': id, 'code': 'R$id', 'name': 'Branch $id', 'active': true};

  CurrentUser admin() => const CurrentUser(
        id: 1,
        username: 'admin',
        fullName: 'Admin',
        role: 'ADMIN',
        privileges: {Privileges.regionView, Privileges.regionManage},
        customerId: null,
        regions: [],
      );

  ProviderContainer containerWith(FakeBackend backend) {
    final c = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(admin()),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  test('the branch map only ever asks the server for a page size it will accept', () async {
    final backend = FakeBackend({
      'GET /api/regions': (_) => page([region(1), region(2)], 0, 1),
    });

    await containerWith(backend).read(regionMapProvider.future);

    final asked = backend
        .sent('GET /api/regions')
        .map((r) => int.parse('${r.queryParameters['size']}'))
        .toList();
    expect(asked, isNotEmpty, reason: 'the map must actually call the server');
    for (final size in asked) {
      expect(allowed, contains(size),
          reason: 'size $size is refused by TableQuery.ALLOWED_SIZES');
    }
  });

  test('a company with more branches than one page still gets all of them', () async {
    final first = List.generate(50, (i) => region(i + 1));
    final second = List.generate(12, (i) => region(i + 51));
    final backend = FakeBackend({
      'GET /api/regions': (options) =>
          '${options.queryParameters['page']}' == '0' ? page(first, 0, 2) : page(second, 1, 2),
    });

    final map = await containerWith(backend).read(regionMapProvider.future);

    expect(map.length, 62, reason: 'both pages must be collected, not just the first');
    expect(map.first.code, 'R1');
    expect(map.last.code, 'R62');
    expect(backend.sent('GET /api/regions').length, 2);
  });

  test('somebody without REGION_VIEW gets an empty map and no call at all', () async {
    final backend = FakeBackend({
      'GET /api/regions': (_) => const FakeFailure(403, {'message': 'Not allowed'}),
    });
    final c = ProviderContainer(overrides: [
      dioProvider.overrideWithValue(backend.dio),
      currentUserProvider.overrideWithValue(
          const CurrentUser(
              id: 2,
              username: 'gopal',
              fullName: 'Gopal',
              role: 'VIEWER',
              privileges: {Privileges.invoiceView},
              customerId: null,
              regions: [])),
    ]);
    addTearDown(c.dispose);

    expect(await c.read(regionMapProvider.future), isEmpty);
    expect(backend.sent('GET /api/regions'), isEmpty);
  });
}
