import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class UnsavedChanges {
  Future<bool> Function()? _confirmDiscard;

  void register(Future<bool> Function() confirmDiscard) => _confirmDiscard = confirmDiscard;

  void unregister(Future<bool> Function() confirmDiscard) {
    if (_confirmDiscard == confirmDiscard) _confirmDiscard = null;
  }

  Future<bool> mayLeave() async {
    final confirm = _confirmDiscard;
    return confirm == null || await confirm();
  }
}

final unsavedChangesProvider = Provider<UnsavedChanges>((ref) => UnsavedChanges());

Future<void> goGuarded(BuildContext context, String location) async {
  final unsaved = ProviderScope.containerOf(context, listen: false).read(unsavedChangesProvider);
  if (!await unsaved.mayLeave()) return;
  if (context.mounted) context.go(location);
}
