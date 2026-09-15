import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/theme.dart';

void main() {
  // An infinite minimum width made a filled button inside a Row fail layout: nothing was painted
  // while its hit area spanned the row, so a click on empty space approved a dispute.
  testWidgets('a filled button beside other widgets in a Row gets a finite size', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.light(),
      home: Scaffold(
        body: Row(
          children: [
            FilledButton.icon(
              icon: const Icon(Icons.check),
              label: const Text('Approve'),
              onPressed: () {},
            ),
            const SizedBox(width: 8),
            OutlinedButton(onPressed: () {}, child: const Text('Deny')),
          ],
        ),
      ),
    ));

    expect(tester.takeException(), isNull);
    final approve = tester.getSize(find.byType(FilledButton));
    expect(approve.width, lessThan(300));
    expect(approve.height, greaterThanOrEqualTo(46));
    expect(find.text('Deny'), findsOneWidget);
  });
}
