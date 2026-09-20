import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/widgets/detail_scaffold.dart';

Future<void> _pumpDetail(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: DetailScaffold(
        title: 'INV-20260915-0374',
        subtitle: 'Acme Industrial Supplies Private Limited • 2026-09-15',
        onBack: () {},
        titleTrailing: [
          const Chip(label: Text('Unpaid')),
          TextButton(onPressed: () {}, child: const Text('Raise dispute')),
        ],
        top: const SizedBox(height: 40),
        tabs: [
          DetailTab(slug: 'history', label: 'History', icon: Icons.history, builder: (_) => const SizedBox()),
        ],
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  // The trailing chips and buttons used to take the width the title needed, leaving it about
  // 75px, and the invoice number broke mid-token across three lines. (The test font draws every
  // glyph as a full square, so this checks the width the title gets, not how many lines it takes.)
  testWidgets('on a phone the title gets the full width and the chips move below it', (tester) async {
    await _pumpDetail(tester, const Size(400, 820));

    final title = tester.getRect(find.text('INV-20260915-0374'));
    final chip = tester.getRect(find.text('Unpaid'));
    expect(title.width, greaterThan(250));
    expect(chip.top, greaterThanOrEqualTo(title.bottom));
  });

  testWidgets('on a desktop a narrow top pane starts at the left edge, not in the middle', (tester) async {
    tester.view.physicalSize = const Size(1366, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: DetailScaffold(
          title: 'Dispute #84',
          top: const Padding(
            padding: EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [Text('Reason')],
            ),
          ),
          tabs: [
            DetailTab(slug: 'email', label: 'Email', icon: Icons.mail_outline, builder: (_) => const SizedBox()),
          ],
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(tester.getRect(find.text('Reason')).left, lessThan(40));
  });

  testWidgets('on a desktop the chips share the title row', (tester) async {
    await _pumpDetail(tester, const Size(1366, 900));

    final title = tester.getRect(find.text('INV-20260915-0374'));
    final chip = tester.getRect(find.text('Unpaid'));
    expect((chip.center.dy - title.center.dy).abs(), lessThan(30));
  });
}
