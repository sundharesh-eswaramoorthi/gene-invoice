import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/widgets/detail_scaffold.dart';

/// The top of a detail page should read at a glance: fields in columns when there is room, rather
/// than one full-width row each that pushes the page into scrolling.
Future<void> _pumpGrid(WidgetTester tester, double width) async {
  tester.view.physicalSize = Size(width, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: Align(
        alignment: Alignment.topLeft,
        child: SizedBox(
          width: width,
          child: const DetailGrid(items: [
            DetailGridItem(label: 'Name', child: Text('name value')),
            DetailGridItem(label: 'Email', child: Text('email value')),
            DetailGridItem(label: 'Phone', child: Text('phone value')),
            DetailGridItem(label: 'Notes', span: 2, child: Text('notes value')),
          ]),
        ),
      ),
    ),
  ));
}

double _rowOf(WidgetTester tester, String label) => tester.getTopLeft(find.text(label)).dy;

double _cellWidth(WidgetTester tester, String value) => tester
    .getSize(find.ancestor(of: find.text(value), matching: find.byType(SizedBox)).first)
    .width;

void main() {
  test('three columns when wide, two on a medium width, one on a phone', () {
    expect(DetailGrid.columnsFor(1200), 3);
    expect(DetailGrid.columnsFor(800), 2);
    expect(DetailGrid.columnsFor(400), 1);
  });

  testWidgets('on a wide page name, email and phone share one row', (tester) async {
    await _pumpGrid(tester, 1200);

    expect(_rowOf(tester, 'Email'), _rowOf(tester, 'Name'));
    expect(_rowOf(tester, 'Phone'), _rowOf(tester, 'Name'));
    expect(_rowOf(tester, 'Notes'), greaterThan(_rowOf(tester, 'Name')));
  });

  testWidgets('a medium page puts two fields on a row', (tester) async {
    await _pumpGrid(tester, 800);

    expect(_rowOf(tester, 'Email'), _rowOf(tester, 'Name'));
    expect(_rowOf(tester, 'Phone'), greaterThan(_rowOf(tester, 'Name')));
  });

  testWidgets('a phone stacks every field', (tester) async {
    await _pumpGrid(tester, 400);

    expect(_rowOf(tester, 'Email'), greaterThan(_rowOf(tester, 'Name')));
    expect(_rowOf(tester, 'Phone'), greaterThan(_rowOf(tester, 'Email')));
  });

  testWidgets('a field that spans two columns is about twice as wide as one that does not',
      (tester) async {
    await _pumpGrid(tester, 1200);

    expect(_cellWidth(tester, 'notes value'), greaterThan(_cellWidth(tester, 'name value') * 1.9));
  });
}
