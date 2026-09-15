import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';

/// One tab in a detail screen's lower half. [builder] runs only once the tab is first
/// opened, so each tab loads its own data lazily (AC-C4).
class DetailTab {
  final String label;
  final IconData icon;

  /// Stable key used in the URL so the selected tab survives a refresh (AC-C3).
  final String slug;
  final WidgetBuilder builder;

  const DetailTab({
    required this.slug,
    required this.label,
    required this.icon,
    required this.builder,
  });
}

/// The shared layout for Customer, Invoice and Payment details: the editable facts on
/// top, the conversation below. On a narrow screen the split becomes one scroll (AC-C6).
class DetailScaffold extends StatefulWidget {
  final String title;
  final String? subtitle;
  final List<Widget> titleTrailing;
  final Widget top;
  final List<DetailTab> tabs;
  final String? initialTabSlug;
  final ValueChanged<String>? onTabChanged;
  final VoidCallback? onBack;

  const DetailScaffold({
    super.key,
    required this.title,
    required this.top,
    required this.tabs,
    this.subtitle,
    this.titleTrailing = const [],
    this.initialTabSlug,
    this.onTabChanged,
    this.onBack,
  });

  @override
  State<DetailScaffold> createState() => _DetailScaffoldState();
}

class _DetailScaffoldState extends State<DetailScaffold> with TickerProviderStateMixin {
  late TabController _controller;

  /// Tabs already opened at least once; their content stays mounted so switching back
  /// does not refetch, and the top section never reloads (AC-C3).
  final Set<int> _visited = {};

  @override
  void initState() {
    super.initState();
    _controller = TabController(length: widget.tabs.length, vsync: this, initialIndex: _initialIndex());
    _visited.add(_controller.index);
    _controller.addListener(() {
      if (_controller.indexIsChanging) return;
      setState(() => _visited.add(_controller.index));
      widget.onTabChanged?.call(widget.tabs[_controller.index].slug);
    });
  }

  int _initialIndex() {
    final slug = widget.initialTabSlug;
    if (slug == null) return 0;
    final i = widget.tabs.indexWhere((t) => t.slug == slug);
    return i < 0 ? 0 : i;
  }

  @override
  void didUpdateWidget(covariant DetailScaffold oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.tabs.length != widget.tabs.length) {
      _controller.dispose();
      _controller =
          TabController(length: widget.tabs.length, vsync: this, initialIndex: _initialIndex());
      _visited
        ..clear()
        ..add(_controller.index);
    } else if (oldWidget.initialTabSlug != widget.initialTabSlug) {
      // The URL moved to another tab — a link, or browser back/forward — so follow it. A change
      // the tab bar made itself already points at the current tab, and this is a no-op.
      final i = _initialIndex();
      if (i != _controller.index) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || i >= _controller.length || i == _controller.index) return;
          setState(() => _visited.add(i));
          _controller.animateTo(i);
        });
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final isNarrow = MediaQuery.sizeOf(context).width < 760;
    final header = _Header(
      title: widget.title,
      subtitle: widget.subtitle,
      trailing: widget.titleTrailing,
      onBack: widget.onBack,
    );

    final tabBar = TabBar(
      controller: _controller,
      isScrollable: true,
      tabAlignment: TabAlignment.start,
      tabs: [
        for (final t in widget.tabs) Tab(icon: Icon(t.icon, size: 18), text: t.label),
      ],
    );

    final tabViews = TabBarView(
      controller: _controller,
      children: [
        for (var i = 0; i < widget.tabs.length; i++)
          // Built only once the tab has been opened, so each loads its own data lazily and a
          // failure inside one stays inside it (AC-C4).
          _visited.contains(i)
              ? Builder(builder: widget.tabs[i].builder)
              : const SizedBox.shrink(),
      ],
    );

    if (isNarrow) {
      // One vertical scroll, with the tab bar pinned so it stays reachable.
      return NestedScrollView(
        headerSliverBuilder: (context, _) => [
          SliverToBoxAdapter(child: header),
          SliverToBoxAdapter(child: widget.top),
          SliverPersistentHeader(
            pinned: true,
            delegate: _PinnedTabBar(tabBar: tabBar, color: Theme.of(context).colorScheme.surface),
          ),
        ],
        body: tabViews,
      );
    }

    return Column(
      children: [
        header,
        Flexible(
          flex: 5,
          child: SingleChildScrollView(child: widget.top),
        ),
        const Divider(height: 1),
        Flexible(
          flex: 5,
          child: Column(
            children: [
              Align(alignment: Alignment.centerLeft, child: tabBar),
              const Divider(height: 1),
              Expanded(child: tabViews),
            ],
          ),
        ),
      ],
    );
  }
}

class _Header extends StatelessWidget {
  final String title;
  final String? subtitle;
  final List<Widget> trailing;
  final VoidCallback? onBack;
  const _Header({required this.title, this.subtitle, this.trailing = const [], this.onBack});

  @override
  Widget build(BuildContext context) {
    final back = onBack == null
        ? null
        : IconButton(tooltip: 'Back', icon: const Icon(Icons.arrow_back), onPressed: onBack);
    final titleBlock = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title,
            style: Theme.of(context).textTheme.headlineSmall,
            maxLines: 2,
            overflow: TextOverflow.ellipsis),
        if (subtitle != null)
          Text(subtitle!,
              style: Theme.of(context).textTheme.bodyMedium,
              maxLines: 2,
              overflow: TextOverflow.ellipsis),
      ],
    );
    final trailingWrap = Wrap(
      spacing: 6,
      runSpacing: 6,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: trailing,
    );

    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 12, 16, 4),
      child: LayoutBuilder(builder: (context, constraints) {
        // On a phone the chips and buttons took the width the title needed, until an invoice
        // number broke mid-token; there they get a line of their own under the title.
        if (constraints.maxWidth < 600) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [if (back != null) back, Expanded(child: titleBlock)]),
              if (trailing.isNotEmpty)
                Padding(
                  padding: EdgeInsets.only(left: back == null ? 0 : 48, top: 4),
                  child: trailingWrap,
                ),
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [if (back != null) back, Expanded(child: titleBlock), trailingWrap],
        );
      }),
    );
  }
}

class _PinnedTabBar extends SliverPersistentHeaderDelegate {
  final TabBar tabBar;
  final Color color;
  const _PinnedTabBar({required this.tabBar, required this.color});

  @override
  double get minExtent => tabBar.preferredSize.height;
  @override
  double get maxExtent => tabBar.preferredSize.height;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) =>
      Material(color: color, child: tabBar);

  @override
  bool shouldRebuild(covariant _PinnedTabBar oldDelegate) =>
      oldDelegate.tabBar != tabBar || oldDelegate.color != color;
}

/// A read-only or editable field row used by the detail screens' top sections.
class DetailField extends StatelessWidget {
  final String label;
  final Widget child;
  final double labelWidth;

  const DetailField({
    super.key,
    required this.label,
    required this.child,
    this.labelWidth = 150,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: labelWidth,
            child: Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(label, style: Theme.of(context).textTheme.labelLarge),
            ),
          ),
          Expanded(child: child),
        ],
      ),
    );
  }
}

/// A read-only value, used where the caller lacks the privilege to edit (AC-C2).
class ReadOnlyValue extends StatelessWidget {
  final String value;
  const ReadOnlyValue(this.value, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Text(value.isEmpty ? '—' : value),
      );
}

/// Distinguishes "does not exist" from "you may not see it", so the caller gets a clean state
/// rather than a crash or an empty shell (AC-C8).
String notFoundMessage(Object error, String noun) {
  if (error is DioException) {
    final status = error.response?.statusCode;
    if (status == 403) return 'You do not have permission to view this $noun.';
    if (status == 404) return 'That $noun does not exist.';
  }
  return 'Could not load this $noun: ${apiErrorMessage(error)}';
}

/// A clean not-found / forbidden state instead of a crash or an empty shell (AC-C8).
class RecordUnavailable extends StatelessWidget {
  final String message;
  final VoidCallback? onBack;
  const RecordUnavailable({super.key, required this.message, this.onBack});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off, size: 48, color: scheme.outline),
            const SizedBox(height: 12),
            Text(message, style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
            if (onBack != null) ...[
              const SizedBox(height: 12),
              FilledButton(onPressed: onBack, child: const Text('Go back')),
            ],
          ],
        ),
      ),
    );
  }
}
