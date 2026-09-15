import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/widgets/search_picker_field.dart';

void main() {
  // Sixty customers plus one sorted last: a first page of 50 by name would never reach it.
  final names = [for (var i = 1; i <= 60; i++) 'Customer ${i.toString().padLeft(2, '0')}', 'zz Last Ltd'];

  testWidgets('searches for what is typed and picks a record beyond the first page', (tester) async {
    final searches = <String>[];
    String? picked;

    Future<List<String>> search(String q) async {
      searches.add(q);
      return names.where((n) => n.toLowerCase().contains(q.toLowerCase())).take(20).toList();
    }

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) => SearchPickerField<String>(
            label: 'Customer',
            required: true,
            value: picked,
            labelOf: (s) => s,
            search: search,
            onChanged: (v) => setState(() => picked = v),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('Select…'));
    await tester.pumpAndSettle();
    expect(find.text('Choose customer'), findsOneWidget);
    expect(find.text('Customer 01'), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'z');
    await tester.enterText(find.byType(TextField), 'zz');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();

    // The list answers the text in the box, not the keystroke before it.
    expect(searches.last, 'zz');
    expect(find.text('Customer 01'), findsNothing);
    await tester.tap(find.text('zz Last Ltd'));
    await tester.pumpAndSettle();

    expect(picked, 'zz Last Ltd');
    expect(find.text('zz Last Ltd'), findsOneWidget);
  });
}
