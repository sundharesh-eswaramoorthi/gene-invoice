import 'package:flutter/material.dart';

import 'poc_providers.dart';

/// A point of contact's name in a list cell.
///
/// Capped and ellipsised like the other name columns, with the whole name on hover — a person's
/// name runs to 120 characters and used to be cut mid-letter against the row actions (UI-08).
///
/// A deactivated holder is marked, as the POC editor and the detail screens mark them: they keep
/// the seat, but PocService skips them, so email and the defaults on new records go to the next
/// active holder. Naming them plainly would name somebody the app would not write to (CP-07,
/// AC-A5).
class PocNameCell extends StatelessWidget {
  final PocUser? user;

  const PocNameCell({super.key, required this.user});

  /// The text this cell shows, for a caller that needs the string rather than the widget.
  static String describe(PocUser? user) =>
      user == null ? '—' : user.display + (user.active ? '' : ' (inactive)');

  @override
  Widget build(BuildContext context) {
    final text = describe(user);
    return Tooltip(
      message: user == null ? '' : text,
      child: Text(text, maxLines: 1, overflow: TextOverflow.ellipsis),
    );
  }
}
