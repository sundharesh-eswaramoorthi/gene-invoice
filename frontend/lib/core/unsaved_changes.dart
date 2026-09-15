import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The screen that may be holding unsaved edits, and how to ask its user about them. Editable
/// detail routes consult it from GoRoute.onExit, so leaving by the sidebar, the drawer, the bell,
/// an in-page link or the browser's Back button prompts exactly as the screen's own Back arrow
/// does (AC-C3).
class UnsavedChanges {
  Future<bool> Function()? _confirmDiscard;

  /// [confirmDiscard] resolves true when the screen may be left: nothing is unsaved, or the user
  /// chose to discard it.
  void register(Future<bool> Function() confirmDiscard) => _confirmDiscard = confirmDiscard;

  /// Forgets [confirmDiscard] if it is still the registered one; a newer screen's stays.
  void unregister(Future<bool> Function() confirmDiscard) {
    if (_confirmDiscard == confirmDiscard) _confirmDiscard = null;
  }

  Future<bool> mayLeave() async {
    final confirm = _confirmDiscard;
    return confirm == null || await confirm();
  }
}

final unsavedChangesProvider = Provider<UnsavedChanges>((ref) => UnsavedChanges());
