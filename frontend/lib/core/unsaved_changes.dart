import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

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

/// In-app navigation that settles unsaved edits first. Asking before go(), rather than letting the
/// route's onExit refuse a navigation already under way, keeps a refused navigation out of the
/// browser history, where its duplicate entry would swallow the next Back press. onExit stays as
/// the safety net for browser Back and any link that navigates directly.
Future<void> goGuarded(BuildContext context, String location) async {
  final unsaved = ProviderScope.containerOf(context, listen: false).read(unsavedChangesProvider);
  if (!await unsaved.mayLeave()) return;
  if (context.mounted) context.go(location);
}
