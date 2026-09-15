import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/table/table_providers.dart';
import 'package:gene_invoice/features/auth/auth_controller.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';

CurrentUser _user(int id, {int? customerId}) => CurrentUser(
      id: id,
      username: 'user$id',
      fullName: null,
      role: customerId == null ? 'ADMIN' : 'CUSTOMER',
      privileges: const {},
      customerId: customerId,
    );

void main() {
  // The server answers each user with their own schema; a customer's has no POC columns. A schema
  // kept from the previous user offered a customer filters the server then refused.
  test('the column list is fetched again when a different user signs in', () async {
    final signedIn = StateProvider<CurrentUser?>((ref) => _user(1));
    var fetches = 0;
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        fetches++;
        final staff = fetches == 1;
        handler.resolve(Response(requestOptions: options, statusCode: 200, data: {
          'entity': 'invoices',
          'columns': [
            {'name': 'invoiceNumber', 'type': 'TEXT'},
            if (staff) {'name': 'salesPocUserId', 'type': 'REFERENCE'},
          ],
        }));
      }));
    final container = ProviderContainer(overrides: [
      currentUserProvider.overrideWith((ref) => ref.watch(signedIn)),
      dioProvider.overrideWithValue(dio),
    ]);
    addTearDown(container.dispose);

    final forAdmin = await container.read(tableSchemaProvider('invoices').future);
    expect(forAdmin.column('salesPocUserId'), isNotNull);

    container.read(signedIn.notifier).state = _user(2, customerId: 7);
    final forCustomer = await container.read(tableSchemaProvider('invoices').future);
    expect(fetches, 2);
    expect(forCustomer.column('salesPocUserId'), isNull);
  });
}
