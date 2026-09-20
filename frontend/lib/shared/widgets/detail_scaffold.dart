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

  /// How many rows the tab holds, shown on its icon, so the page says there is something there
  /// before the tab is opened (documents, AC-C1). Null shows no badge; 0 shows the tab plainly.
  final int? badgeCount;

  const DetailTab({
    required this.slug,
    required this.label,
    required this.icon,
    required this.builder,
    this.badgeCount,
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

  /// The top pane scrolls only when its content is taller than the room it may take (about half
  /// the page); its scrollbar is then the cue that there is more below (D-64).
  final ScrollController _topScroll = ScrollController();

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
    _topScroll.dispose();
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
        for (final t in widget.tabs) Tab(icon: _TabIcon(tab: t), text: t.label),
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

    return LayoutBuilder(
      builder: (context, constraints) => Column(
        // Stretched, so a top pane narrower than the page starts at its left edge under the title
        // instead of being centred in it — a pending dispute's facts sat in the middle of the page.
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          header,
          // The top pane is as tall as its content, not a fixed half of the page, so an ordinary
          // record shows in full with nothing to scroll. Only a genuinely tall top (or a short
          // window) scrolls, and it never takes more than about half the page from the tabs.
          ConstrainedBox(
            constraints: BoxConstraints(maxHeight: constraints.maxHeight * 0.55),
            child: Scrollbar(
              controller: _topScroll,
              thumbVisibility: true,
              child: SingleChildScrollView(controller: _topScroll, child: widget.top),
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: Column(
              children: [
                Align(alignment: Alignment.centerLeft, child: tabBar),
                const Divider(height: 1),
                Expanded(child: tabViews),
              ],
            ),
          ),
        ],
      ),
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

/// A tab's icon, with its count on it when it has one. An empty tab keeps the plain icon: a "0"
/// badge is noise where the tab itself already says there is nothing.
class _TabIcon extends StatelessWidget {
  final DetailTab tab;
  const _TabIcon({required this.tab});

  @override
  Widget build(BuildContext context) {
    final icon = Icon(tab.icon, size: 18);
    final count = tab.badgeCount;
    if (count == null || count <= 0) return icon;
    return Badge.count(count: count, child: icon);
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

/// One cell of a [DetailGrid]: a label above its field.
class DetailGridItem {
  final String label;
  final Widget child;

  /// How many columns the cell spans when the grid has that many (a notes field across two, say).
  final int span;

  const DetailGridItem({required this.label, required this.child, this.span = 1});
}

/// Lays a detail page's fields out in columns rather than one full-width row each, so the top of
/// the page shows a record at a glance: three columns when there is room, two on a medium window,
/// one on a phone. Labels sit above their fields, which saves the width a side label would take.
class DetailGrid extends StatelessWidget {
  final List<DetailGridItem> items;
  final double spacing;

  const DetailGrid({super.key, required this.items, this.spacing = 16});

  /// Columns for a grid this wide. Public so a test can pin the breakpoints.
  static int columnsFor(double width) => width >= 1000 ? 3 : (width >= 640 ? 2 : 1);

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final columns = columnsFor(constraints.maxWidth);
      // Floored so rounding can never push the last cell of a row onto the next one.
      final unit = ((constraints.maxWidth - spacing * (columns - 1)) / columns).floorToDouble();
      return Wrap(
        spacing: spacing,
        runSpacing: 12,
        children: [
          for (final item in items)
            SizedBox(
              width: unit * _span(item, columns) + spacing * (_span(item, columns) - 1),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(item.label, style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 6),
                  item.child,
                ],
              ),
            ),
        ],
      );
    });
  }

  static int _span(DetailGridItem item, int columns) =>
      item.span < 1 ? 1 : (item.span > columns ? columns : item.span);
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
