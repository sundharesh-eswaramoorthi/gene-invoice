import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/features/poc/poc_picker.dart';
import 'package:gene_invoice/features/poc/poc_providers.dart';

void main() {
  const users = [
    {'id': 1, 'username': 'abe', 'fullName': 'Abe Sales', 'role': 'SALES_POC', 'active': true},
    {'id': 2, 'username': 'zed', 'fullName': 'Zed Sales', 'role': 'SALES_POC', 'active': true},
  ];

  testWidgets('the POC search follows exactly what is typed', (tester) async {
    final searches = <String?>[];
    final dio = Dio()
      ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
        final q = options.queryParameters['q'] as String?;
        searches.add(q);
        final matches = users.where((u) => q == null || (u['username'] as String).contains(q)).toList();
        handler.resolve(Response(requestOptions: options, statusCode: 200, data: matches));
      }));

    PocUser? picked;
    await tester.pumpWidget(ProviderScope(
      overrides: [dioProvider.overrideWithValue(dio)],
      child: MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => PocPicker(
              type: PocType.SALES,
              value: picked,
              required: true,
              onChanged: (u) => setState(() => picked = u),
            ),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('Select…'));
    await tester.pumpAndSettle();
    expect(find.text('Abe Sales'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'ze');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(searches.last, 'ze');
    expect(find.text('Abe Sales'), findsNothing);

    await tester.tap(find.text('Zed Sales'));
    await tester.pumpAndSettle();
    expect(picked?.id, 2);
  });
}
