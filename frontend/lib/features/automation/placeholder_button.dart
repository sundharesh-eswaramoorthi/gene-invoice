import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import 'automation_models.dart';
import 'automation_providers.dart';

/// "Insert placeholder": the `{{…}}` values a text about [entityType] may use (A4), grouped by
/// level — the customer's, the record's own, the general ones. The one picked goes in at the
/// cursor of [controller], in place of any selected text; with [singleLine] (a subject) nothing
/// inserted can bring a line break with it.
///
/// The cursor used is the one the field had when the button was pressed: on the web, closing the
/// menu gives the field its focus back and a single-line field selects all of its text when that
/// happens, which may come before or after the pick — so the selection at pick time can't be
/// trusted, and a select-all that lands just after the insert is put back to a caret.
///
/// On the web a mouse press on the button takes the focus away from the field, and the menu's
/// route hands it back to whatever had it (nothing, or the button itself with semantics on) — so
/// once the placeholder is in, [focusNode] is asked for the focus again after that has happened.
class PlaceholderButton extends ConsumerStatefulWidget {
  final String entityType;
  final TextEditingController controller;
  final bool singleLine;

  /// The text field's own focus node — the one given to its `TextField(focusNode:)`. It is given
  /// its focus back once the placeholder is in (or the menu is closed without a pick), so typing
  /// carries on at the caret.
  final FocusNode focusNode;
  final bool enabled;
  final ValueChanged<String>? onInserted;

  /// Show nothing at all until there are placeholders to offer, and when there are none — for a
  /// form where they are an extra, such as the manual email compose, rather than the point.
  final bool hideWhenUnavailable;

  /// Leaves out the placeholders whose key (`Rule.Name`, without the braces) this says yes to,
  /// and any group that leaves empty: a hand-written email has no rule to name, say.
  final bool Function(String key)? exclude;

  const PlaceholderButton({
    super.key,
    required this.entityType,
    required this.controller,
    this.singleLine = false,
    required this.focusNode,
    this.enabled = true,
    this.onInserted,
    this.hideWhenUnavailable = false,
    this.exclude,
  });

  List<PlaceholderGroup> _offered(PlaceholderCatalogue? catalogue) {
    final groups = catalogue?.groups ?? const <PlaceholderGroup>[];
    final drop = exclude;
    if (drop == null) return groups;
    return [
      for (final g in groups)
        if (g.items.where((i) => !drop(i.key)).toList() case final items when items.isNotEmpty)
          PlaceholderGroup(level: g.level, label: g.label, items: items),
    ];
  }

  @override
  ConsumerState<PlaceholderButton> createState() => _PlaceholderButtonState();
}

class _PlaceholderButtonState extends ConsumerState<PlaceholderButton> {
  /// The field's text and selection when the menu was asked for.
  (String, TextSelection)? _captured;

  VoidCallback? _caretGuard;
  VoidCallback? _focusGuard;
  FocusNode? _guardedNode;
  Timer? _caretGuardTimer;

  void _capture() => _captured = (widget.controller.text, widget.controller.selection);

  void _clearCaretGuard([TextEditingController? controller]) {
    final guard = _caretGuard;
    if (guard != null) (controller ?? widget.controller).removeListener(guard);
    _caretGuard = null;
    final focusGuard = _focusGuard;
    if (focusGuard != null) _guardedNode?.removeListener(focusGuard);
    _focusGuard = null;
    _guardedNode = null;
    _caretGuardTimer?.cancel();
    _caretGuardTimer = null;
  }

  /// For about a second after an insert that left [text] with the caret at [offset], every
  /// selection of the whole of [text] (the web field selecting all each time it gets its focus
  /// back) is turned back into that caret. A change to the text, the field losing its focus once
  /// it has had it, or the second running out ends it.
  void _guardCaret(String text, int offset) {
    _clearCaretGuard();
    final controller = widget.controller;
    final node = widget.focusNode;
    void guard() {
      if (controller.text != text) {
        _clearCaretGuard();
        return;
      }
      final sel = controller.selection;
      if (!sel.isCollapsed && sel.start == 0 && sel.end == text.length) {
        controller.selection = TextSelection.collapsed(offset: offset);
      }
    }

    var hadFocus = node.hasFocus;
    void focusGuard() {
      if (node.hasFocus) {
        hadFocus = true;
      } else if (hadFocus) {
        _clearCaretGuard();
      }
    }

    _caretGuard = guard;
    controller.addListener(guard);
    _focusGuard = focusGuard;
    _guardedNode = node;
    node.addListener(focusGuard);
    _caretGuardTimer = Timer(const Duration(seconds: 1), _clearCaretGuard);
  }

  /// Asks for the field's focus once this frame is done, so it comes after the menu's route has
  /// put the focus back where it was when the menu opened.
  void _refocusAfterFrame() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      widget.focusNode.requestFocus();
    });
  }

  void _onSelected(PlaceholderItem item) {
    final captured = _captured;
    _captured = null;
    final controller = widget.controller;
    final at = captured != null && captured.$1 == controller.text && captured.$2.isValid
        ? captured.$2
        : const TextSelection.collapsed(offset: -1);
    final next = insertPlaceholder(controller, item.token,
        at: at,
        singleLine: widget.singleLine,
        focusNode: widget.focusNode,
        onInserted: widget.onInserted);
    final caret = controller.selection;
    if (caret.isValid && caret.isCollapsed) _guardCaret(next, caret.baseOffset);
    _refocusAfterFrame();
  }

  /// The menu closed without a pick: the field gets its focus back, and its cursor as it was
  /// when the button was pressed while its text is still the same.
  void _onCanceled() {
    final captured = _captured;
    _captured = null;
    final controller = widget.controller;
    widget.focusNode.requestFocus();
    if (captured != null && captured.$1 == controller.text && captured.$2.isValid) {
      final sel = captured.$2;
      controller.selection = sel;
      if (sel.isCollapsed) _guardCaret(controller.text, sel.baseOffset);
    }
    _refocusAfterFrame();
  }

  @override
  void didUpdateWidget(PlaceholderButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.controller, widget.controller) ||
        !identical(oldWidget.focusNode, widget.focusNode)) {
      _clearCaretGuard(oldWidget.controller);
      _captured = null;
    }
  }

  @override
  void dispose() {
    _clearCaretGuard();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(placeholdersProvider(widget.entityType));
    final catalogue = async.valueOrNull;
    final groups = widget._offered(catalogue);
    if (widget.hideWhenUnavailable && groups.isEmpty) {
      return const SizedBox.shrink();
    }
    final tooltip = async.hasError
        ? 'Placeholders could not be loaded: ${apiErrorMessage(async.error!)}'
        : 'Insert placeholder';
    final button = PopupMenuButton<PlaceholderItem>(
      tooltip: tooltip,
      enabled: widget.enabled && groups.isNotEmpty,
      icon: const Icon(Icons.data_object),
      // Opened from the keyboard there was no pointer down to capture the field's cursor at.
      onOpened: () {
        if (_captured == null) _capture();
      },
      onCanceled: _onCanceled,
      onSelected: _onSelected,
      itemBuilder: (context) => [
        for (final (i, group) in groups.indexed) ...[
          if (i > 0) const PopupMenuDivider(),
          PopupMenuItem<PlaceholderItem>(
            enabled: false,
            height: 32,
            child: Text(group.label, style: Theme.of(context).textTheme.labelMedium),
          ),
          for (final item in group.items)
            PopupMenuItem<PlaceholderItem>(
              value: item,
              child: Text(item.label),
            ),
        ],
      ],
    );
    // Taken as the button is pressed, before the menu's route can move the field's focus.
    return Listener(onPointerDown: (_) => _capture(), child: button);
  }
}

/// Puts [token] into [controller] at [at] — its current selection when not given — in place of
/// any text selected there (at the end when there is no valid selection), leaves the cursor just
/// after it, and gives back the new text.
String insertPlaceholder(TextEditingController controller, String token,
    {TextSelection? at,
    bool singleLine = false,
    FocusNode? focusNode,
    ValueChanged<String>? onInserted}) {
  final insert = singleLine ? token.replaceAll(RegExp(r'[\r\n]+'), ' ') : token;
  final text = controller.text;
  final sel = at ?? controller.selection;
  final start = sel.isValid ? sel.start.clamp(0, text.length) : text.length;
  final end = sel.isValid ? sel.end.clamp(start, text.length) : text.length;
  final next = text.replaceRange(start, end, insert);
  controller.value = TextEditingValue(
    text: next,
    selection: TextSelection.collapsed(offset: start + insert.length),
  );
  focusNode?.requestFocus();
  onInserted?.call(next);
  return next;
}
