import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';

class DetailTab {
  final String label;
  final IconData icon;

  final String slug;
  final WidgetBuilder builder;

  final int? badgeCount;

  const DetailTab({
    required this.slug,
    required this.label,
    required this.icon,
    required this.builder,
    this.badgeCount,
  });
}

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
          _visited.contains(i)
              ? Builder(builder: widget.tabs[i].builder)
              : const SizedBox.shrink(),
      ],
    );

    if (isNarrow) {
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
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          header,
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

class DetailGridItem {
  final String label;
  final Widget child;

  final int span;

  const DetailGridItem({required this.label, required this.child, this.span = 1});
}

class DetailGrid extends StatelessWidget {
  final List<DetailGridItem> items;
  final double spacing;

  const DetailGrid({super.key, required this.items, this.spacing = 16});

  static int columnsFor(double width) => width >= 1000 ? 3 : (width >= 640 ? 2 : 1);

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final columns = columnsFor(constraints.maxWidth);
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

class ReadOnlyValue extends StatelessWidget {
  final String value;
  const ReadOnlyValue(this.value, {super.key});

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Text(value.isEmpty ? '—' : value),
      );
}

String notFoundMessage(Object error, String noun) {
  if (error is DioException) {
    final status = error.response?.statusCode;
    if (status == 403) return 'You do not have permission to view this $noun.';
    if (status == 404) return 'That $noun does not exist.';
  }
  return 'Could not load this $noun: ${apiErrorMessage(error)}';
}

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
