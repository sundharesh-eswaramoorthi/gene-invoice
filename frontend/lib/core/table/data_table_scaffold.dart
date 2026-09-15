import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../api/api_client.dart';
import 'filter_editor.dart';
import 'table_models.dart';
import 'table_providers.dart';

/// One rendered column. [sortKey] is the backend column name; null means the column
/// cannot be sorted and is shown as such (AC-D3).
class TableColumnSpec<T> {
  final String label;
  final String? sortKey;
  final bool numeric;

  /// Caps the column's width on the desktop table, for free text that would otherwise stretch
  /// the column to its longest value. The cell wraps and ends in an ellipsis within it.
  final double? maxWidth;
  final Widget Function(BuildContext context, T row) cell;

  const TableColumnSpec({
    required this.label,
    required this.cell,
    this.sortKey,
    this.numeric = false,
    this.maxWidth,
  });
}

/// A bulk action offered in the selection toolbar.
class BulkActionSpec {
  final String action;
  final String label;
  final IconData icon;
  final bool destructive;

  /// Collects extra parameters. Return null to abandon the action.
  final Future<Map<String, dynamic>?> Function(BuildContext context)? buildParams;

  const BulkActionSpec({
    required this.action,
    required this.label,
    required this.icon,
    this.destructive = false,
    this.buildParams,
  });
}

/// The list-page frame every table shares: filter-aware tiles, a filter bar, selection and
/// bulk actions, a responsive body and server-side pagination.
class DataTableScaffold<T> extends ConsumerStatefulWidget {
  final String entity;
  final String path;
  final TableQuery query;
  final ValueChanged<TableQuery> onQueryChanged;
  final Map<String, dynamic> extraParams;

  final T Function(Map<String, dynamic> json) parse;
  final int Function(T row) idOf;
  final List<TableColumnSpec<T>> columns;

  final void Function(BuildContext context, T row)? onRowTap;
  final List<Widget> Function(BuildContext context, T row)? rowActions;
  final Widget Function(BuildContext context, T row)? mobileCard;

  final Widget Function(BuildContext context, Map<String, dynamic> summary)? tiles;
  final List<BulkActionSpec> bulkActions;
  final bool canExport;

  /// Lets a screen switch row selection off. It is off anyway when the caller has neither a bulk
  /// action nor export, since a selection would lead nowhere.
  final bool selectable;

  final String emptyMessage;
  final Widget? header;

  /// Page-level actions such as "New invoice", shown at the end of the filter bar. They sit in
  /// the layout rather than floating over it, so they can never cover the pagination controls.
  final List<Widget> actions;

  const DataTableScaffold({
    super.key,
    required this.entity,
    required this.path,
    required this.query,
    required this.onQueryChanged,
    required this.parse,
    required this.idOf,
    required this.columns,
    this.extraParams = const {},
    this.onRowTap,
    this.rowActions,
    this.mobileCard,
    this.tiles,
    this.bulkActions = const [],
    this.canExport = false,
    this.selectable = true,
    this.emptyMessage = 'No rows match this filter',
    this.header,
    this.actions = const [],
  });

  @override
  ConsumerState<DataTableScaffold<T>> createState() => _DataTableScaffoldState<T>();
}

class _DataTableScaffoldState<T> extends ConsumerState<DataTableScaffold<T>> {
  final Set<int> _selected = {};
  bool _selectAllMatching = false;
  bool _busy = false;
  final _hScroll = ScrollController();

  /// Rows are selectable whenever there is something to do with a selection — a bulk action or
  /// export — so a role that may export but not manage can still reach "Export selected".
  bool get _selectable => widget.selectable && (widget.bulkActions.isNotEmpty || widget.canExport);

  @override
  void dispose() {
    _hScroll.dispose();
    super.dispose();
  }

  TableRequest get _request => TableRequest(
        entity: widget.entity,
        path: widget.path,
        query: widget.query,
        extra: widget.extraParams,
      );

  @override
  void didUpdateWidget(covariant DataTableScaffold<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.query != widget.query) {
      _clearSelection();
    }
  }

  void _clearSelection() {
    if (_selected.isEmpty && !_selectAllMatching) return;
    setState(() {
      _selected.clear();
      _selectAllMatching = false;
    });
  }

  void _refresh() {
    ref.invalidate(tablePageProvider(_request));
    ref.invalidate(tableSummaryProvider(_request));
    _clearSelection();
  }

  @override
  Widget build(BuildContext context) {
    final pageAsync = ref.watch(tablePageProvider(_request));
    final schemaAsync = ref.watch(tableSchemaProvider(widget.entity));
    final schema = schemaAsync.valueOrNull;
    final isNarrow = MediaQuery.sizeOf(context).width < 760;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.header != null) widget.header!,
        if (widget.tiles != null) _SummaryTiles(request: _request, builder: widget.tiles!),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: _FilterBar(
                schema: schema,
                query: widget.query,
                lockedFilters: pageAsync.valueOrNull?.lockedFilters ?? const [],
                onQueryChanged: (q) => widget.onQueryChanged(q),
              ),
            ),
            if (widget.actions.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(0, 8, 12, 8),
                // Page actions sit beside the filter bar, so they take a compact height that
                // lines up with its chips.
                child: Theme(
                  data: Theme.of(context).copyWith(
                    filledButtonTheme: FilledButtonThemeData(
                      style: FilledButton.styleFrom(minimumSize: const Size(64, 40))
                          .merge(Theme.of(context).filledButtonTheme.style),
                    ),
                  ),
                  child: Wrap(spacing: 8, runSpacing: 8, children: widget.actions),
                ),
              ),
          ],
        ),
        if (_selected.isNotEmpty || _selectAllMatching)
          _SelectionToolbar(
            selectedCount: _selected.length,
            totalMatching: pageAsync.valueOrNull?.totalElements ?? 0,
            selectAllMatching: _selectAllMatching,
            busy: _busy,
            actions: widget.bulkActions,
            canExport: widget.canExport,
            onSelectAllMatching: () => setState(() => _selectAllMatching = true),
            onClear: _clearSelection,
            onRun: _runBulkAction,
            onExport: _runExport,
          ),
        Expanded(
          child: pageAsync.when(
            loading: () => const _LoadingState(),
            error: (e, _) => _ErrorState(message: apiErrorMessage(e), onRetry: _refresh),
            data: (page) {
              // An empty page with rows behind it is a page past the end (an old link, or the
              // last page emptied by a bulk action) — not a filter that matches nothing.
              if (page.isEmpty && page.totalElements > 0) {
                return _PastTheEndState(
                  totalElements: page.totalElements,
                  totalPages: page.totalPages,
                  onLastPage: () =>
                      widget.onQueryChanged(widget.query.copyWith(page: page.totalPages - 1)),
                );
              }
              if (page.isEmpty) {
                return _EmptyState(
                  message: widget.query.hasFilters
                      ? widget.emptyMessage
                      : 'Nothing here yet',
                  hasFilters: widget.query.hasFilters,
                  onClearFilters: () => widget.onQueryChanged(widget.query.withFilters(const [])),
                );
              }
              final rows = page.content.map(widget.parse).toList();
              return RefreshIndicator(
                onRefresh: () async => _refresh(),
                child: isNarrow ? _cardList(rows) : _dataTable(rows, schema),
              );
            },
          ),
        ),
        pageAsync.maybeWhen(
          data: (page) => _PaginationBar(
            page: page,
            pageSizes: schema?.pageSizes ?? const [10, 20, 50],
            onPage: (p) => widget.onQueryChanged(widget.query.copyWith(page: p)),
            onSize: (s) {
              ref.read(pageSizeStoreProvider.notifier).write(widget.entity, s);
              widget.onQueryChanged(widget.query.withSize(s));
            },
          ),
          orElse: () => const SizedBox.shrink(),
        ),
      ],
    );
  }

  // ---- body -------------------------------------------------------------------

  Widget _dataTable(List<T> rows, TableSchema? schema) {
    final sortField = widget.query.sort?.split(',').first;
    final ascending = !(widget.query.sort?.endsWith('desc') ?? false);
    final sortIndex = sortField == null
        ? null
        : widget.columns.indexWhere((c) => c.sortKey == sortField);

    // Sized to the space the table actually has — beside the navigation rail, not the whole
    // window — so the last columns and the row actions start on screen. When the columns need
    // more room, a scrollbar that is always visible says so.
    return LayoutBuilder(
      builder: (context, constraints) => Scrollbar(
        controller: _hScroll,
        thumbVisibility: true,
        notificationPredicate: (n) => n.depth == 1,
        child: SingleChildScrollView(
          scrollDirection: Axis.vertical,
          child: SingleChildScrollView(
            controller: _hScroll,
            scrollDirection: Axis.horizontal,
            child: ConstrainedBox(
              constraints: BoxConstraints(minWidth: constraints.maxWidth),
              child: DataTable(
                // Material's 56px gaps alone cost ~300px on a seven-column table, enough to push
                // the row actions off-screen at 1366px (D-19, D-20).
                columnSpacing: 24,
                showCheckboxColumn: _selectable,
                sortColumnIndex: (sortIndex != null && sortIndex >= 0) ? sortIndex : null,
                sortAscending: ascending,
                columns: [
                  for (final c in widget.columns)
                    DataColumn(
                      label: _capped(c, Text(c.label,
                          style: const TextStyle(fontWeight: FontWeight.w600))),
                      numeric: c.numeric,
                      onSort: c.sortKey == null
                          ? null
                          : (index, asc) => widget.onQueryChanged(
                              widget.query.withSort('${c.sortKey},${asc ? 'asc' : 'desc'}')),
                    ),
                  if (widget.rowActions != null)
                    const DataColumn(label: Text('')),
                ],
                rows: [
                  for (final row in rows)
                    DataRow(
                      selected: _selected.contains(widget.idOf(row)),
                      onSelectChanged: _selectable
                          ? (on) => setState(() {
                                _selectAllMatching = false;
                                if (on == true) {
                                  _selected.add(widget.idOf(row));
                                } else {
                                  _selected.remove(widget.idOf(row));
                                }
                              })
                          : null,
                      cells: [
                        for (final c in widget.columns)
                          DataCell(
                            _capped(c, c.cell(context, row)),
                            onTap: widget.onRowTap == null
                                ? null
                                : () => widget.onRowTap!(context, row),
                          ),
                        if (widget.rowActions != null)
                          DataCell(Row(
                            mainAxisSize: MainAxisSize.min,
                            children: widget.rowActions!(context, row),
                          )),
                      ],
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _capped(TableColumnSpec<T> c, Widget child) => c.maxWidth == null
      ? child
      : ConstrainedBox(constraints: BoxConstraints(maxWidth: c.maxWidth!), child: child);

  Widget _cardList(List<T> rows) {
    return ListView.separated(
      padding: const EdgeInsets.all(8),
      itemCount: rows.length,
      separatorBuilder: (_, __) => const SizedBox(height: 6),
      itemBuilder: (context, i) {
        final row = rows[i];
        final id = widget.idOf(row);
        return Card(
          margin: EdgeInsets.zero,
          child: InkWell(
            onTap: widget.onRowTap == null ? null : () => widget.onRowTap!(context, row),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (_selectable)
                    Checkbox(
                      value: _selected.contains(id),
                      onChanged: (on) => setState(() {
                        _selectAllMatching = false;
                        if (on == true) {
                          _selected.add(id);
                        } else {
                          _selected.remove(id);
                        }
                      }),
                    ),
                  Expanded(
                    child: widget.mobileCard != null
                        ? widget.mobileCard!(context, row)
                        : Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              for (final c in widget.columns)
                                Padding(
                                  padding: const EdgeInsets.symmetric(vertical: 2),
                                  child: Row(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      SizedBox(
                                        width: 110,
                                        child: Text(c.label,
                                            style: Theme.of(context).textTheme.bodySmall),
                                      ),
                                      Expanded(child: c.cell(context, row)),
                                    ],
                                  ),
                                ),
                            ],
                          ),
                  ),
                  if (widget.rowActions != null)
                    Column(children: widget.rowActions!(context, row)),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  // ---- bulk actions -----------------------------------------------------------

  Future<void> _runBulkAction(BulkActionSpec spec) async {
    Map<String, dynamic>? params;
    if (spec.buildParams != null) {
      params = await spec.buildParams!(context);
      if (params == null) return;
    }
    if (!mounted) return;

    if (!context.mounted) return;
    final count = _selectAllMatching
        ? (ref.read(tablePageProvider(_request)).valueOrNull?.totalElements ?? 0)
        : _selected.length;
    final confirmed = await _confirm(
      title: '${spec.label}?',
      // The exact count is stated before the user commits (AC-D7).
      message: 'This will apply "${spec.label}" to $count record${count == 1 ? '' : 's'}.'
          '${spec.destructive ? '\n\nThis cannot be undone.' : ''}',
      destructive: spec.destructive,
    );
    if (confirmed != true || !context.mounted) return;

    setState(() => _busy = true);
    try {
      final res = await ref.read(dioProvider).post('${widget.path}/bulk', data: {
        'action': spec.action,
        if (!_selectAllMatching) 'ids': _selected.toList(),
        if (_selectAllMatching) 'selectAllMatchingFilter': true,
        'sort': widget.query.sort,
        'filters': widget.query.filters.map((f) => f.wire).toList(),
        if (params != null) 'params': params,
      });
      if (mounted) {
        _showBulkResult((res.data as Map).cast<String, dynamic>());
        _refresh();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _runExport() async {
    setState(() => _busy = true);
    try {
      final res = await ref.read(dioProvider).post('${widget.path}/export', data: {
        'action': 'EXPORT',
        if (!_selectAllMatching) 'ids': _selected.toList(),
        if (_selectAllMatching) 'selectAllMatchingFilter': true,
        'sort': widget.query.sort,
        'filters': widget.query.filters.map((f) => f.wire).toList(),
      });
      if (mounted) _showCsv(res.data.toString());
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(apiErrorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _showCsv(String csv) {
    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Exported CSV'),
        content: SizedBox(
          width: 560,
          height: 360,
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: SingleChildScrollView(
              child: SelectableText(csv,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            ),
          ),
        ),
        actions: [
          TextButton.icon(
            icon: const Icon(Icons.copy),
            label: const Text('Copy'),
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(dialogContext);
              await Clipboard.setData(ClipboardData(text: csv));
              messenger.showSnackBar(
                  const SnackBar(content: Text('CSV copied to clipboard')));
            },
          ),
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Close')),
        ],
      ),
    );
  }

  /// Shows exactly which rows succeeded, failed and were skipped — nothing is dropped
  /// silently (AC-D5, AC-D6).
  void _showBulkResult(Map<String, dynamic> result) {
    final succeeded = ((result['succeeded'] as List?) ?? const []).length;
    final failed = ((result['failed'] as List?) ?? const []).cast<Map<String, dynamic>>();
    final skipped = ((result['skipped'] as List?) ?? const []).cast<Map<String, dynamic>>();
    final truncated = result['truncated'] as bool? ?? false;

    if (failed.isEmpty && skipped.isEmpty && !truncated) {
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$succeeded record${succeeded == 1 ? '' : 's'} updated')));
      return;
    }

    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('${result['action']} result'),
        content: SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('$succeeded succeeded, ${failed.length} failed, '
                    '${skipped.length} skipped, of ${result['requested']} requested.'),
                if (truncated)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'The filtered set was larger than the ${result['limit']} records this '
                      'action handles at once. Narrow the filter and run it again for the rest.',
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                  ),
                if (failed.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Failed', style: Theme.of(context).textTheme.titleSmall),
                  for (final f in failed) Text('#${f['id']} — ${f['reason']}'),
                ],
                if (skipped.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Skipped', style: Theme.of(context).textTheme.titleSmall),
                  for (final s in skipped) Text('#${s['id']} — ${s['reason']}'),
                ],
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(), child: const Text('Close')),
        ],
      ),
    );
  }

  Future<bool?> _confirm({
    required String title,
    required String message,
    bool destructive = false,
  }) {
    return showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Cancel')),
          FilledButton(
            style: destructive
                ? FilledButton.styleFrom(
                    backgroundColor: Theme.of(dialogContext).colorScheme.error)
                : null,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Confirm'),
          ),
        ],
      ),
    );
  }
}

// ---- pieces ---------------------------------------------------------------------

class _SummaryTiles extends ConsumerWidget {
  final TableRequest request;
  final Widget Function(BuildContext, Map<String, dynamic>) builder;
  const _SummaryTiles({required this.request, required this.builder});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(tableSummaryProvider(request));
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      child: async.when(
        // Tiles show their own loading rather than a value that no longer matches (AC-E2).
        loading: () => const SizedBox(
          height: 84,
          child: Center(child: SizedBox(
              width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
        ),
        error: (e, _) => SizedBox(
          height: 84,
          child: Center(child: Text('Summary unavailable: ${apiErrorMessage(e)}')),
        ),
        data: (summary) => builder(context, summary),
      ),
    );
  }
}

/// A single summary tile.
class SummaryTile extends StatelessWidget {
  final String label;
  final String value;
  final IconData? icon;
  final Color? accent;

  const SummaryTile({
    super.key,
    required this.label,
    required this.value,
    this.icon,
    this.accent,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 170,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              if (icon != null) ...[
                Icon(icon, size: 14, color: accent ?? scheme.onSurfaceVariant),
                const SizedBox(width: 4),
              ],
              Expanded(
                child: Text(label,
                    style: Theme.of(context).textTheme.labelMedium,
                    overflow: TextOverflow.ellipsis),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(value,
              style: Theme.of(context)
                  .textTheme
                  .titleLarge
                  ?.copyWith(color: accent, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}

class _FilterBar extends StatelessWidget {
  final TableSchema? schema;
  final TableQuery query;
  final List<TableFilter> lockedFilters;
  final ValueChanged<TableQuery> onQueryChanged;

  const _FilterBar({
    required this.schema,
    required this.query,
    required this.lockedFilters,
    required this.onQueryChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          ActionChip(
            avatar: const Icon(Icons.filter_alt_outlined, size: 18),
            label: const Text('Add filter'),
            onPressed: schema == null
                ? null
                : () async {
                    final f = await showFilterEditor(context: context, schema: schema!);
                    if (f != null) onQueryChanged(query.addFilter(f));
                  },
          ),
          // A scope the server pins on is shown as locked rather than silently absent (AC-A6).
          for (final locked in lockedFilters)
            Tooltip(
              message: 'Your role limits this list to your own records',
              child: Chip(
                avatar: const Icon(Icons.lock_outline, size: 16),
                label: Text(_lockedLabel(locked)),
              ),
            ),
          for (final f in query.filters)
            InputChip(
              label: Text(describeFilter(f, schema)),
              onPressed: schema == null
                  ? null
                  : () async {
                      final edited =
                          await showFilterEditor(context: context, schema: schema!, existing: f);
                      if (edited != null) {
                        onQueryChanged(query.removeFilter(f).addFilter(edited));
                      }
                    },
              onDeleted: () => onQueryChanged(query.removeFilter(f)),
            ),
          if (query.hasFilters)
            TextButton(
              onPressed: () => onQueryChanged(query.withFilters(const [])),
              child: const Text('Clear all'),
            ),
        ],
      ),
    );
  }

  String _lockedLabel(TableFilter f) {
    if (f.field == 'myBook') return 'My book only';
    return 'My records only';
  }
}

class _SelectionToolbar extends StatelessWidget {
  final int selectedCount;
  final int totalMatching;
  final bool selectAllMatching;
  final bool busy;
  final List<BulkActionSpec> actions;
  final bool canExport;
  final VoidCallback onSelectAllMatching;
  final VoidCallback onClear;
  final ValueChanged<BulkActionSpec> onRun;
  final VoidCallback onExport;

  const _SelectionToolbar({
    required this.selectedCount,
    required this.totalMatching,
    required this.selectAllMatching,
    required this.busy,
    required this.actions,
    required this.canExport,
    required this.onSelectAllMatching,
    required this.onClear,
    required this.onRun,
    required this.onExport,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final count = selectAllMatching ? totalMatching : selectedCount;
    return Material(
      color: scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Wrap(
          spacing: 8,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text('$count selected', style: const TextStyle(fontWeight: FontWeight.w600)),
            if (!selectAllMatching && totalMatching > selectedCount)
              TextButton(
                onPressed: onSelectAllMatching,
                child: Text('Select all $totalMatching matching this filter'),
              ),
            if (busy)
              const SizedBox(
                  width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
            for (final a in actions)
              TextButton.icon(
                icon: Icon(a.icon, size: 18),
                label: Text(a.label),
                style: a.destructive
                    ? TextButton.styleFrom(foregroundColor: scheme.error)
                    : null,
                onPressed: busy ? null : () => onRun(a),
              ),
            if (canExport)
              TextButton.icon(
                icon: const Icon(Icons.download_outlined, size: 18),
                label: const Text('Export selected'),
                onPressed: busy ? null : onExport,
              ),
            TextButton(onPressed: busy ? null : onClear, child: const Text('Clear')),
          ],
        ),
      ),
    );
  }
}

class _PaginationBar extends StatelessWidget {
  final PagedResult<Map<String, dynamic>> page;
  final List<int> pageSizes;
  final ValueChanged<int> onPage;
  final ValueChanged<int> onSize;

  const _PaginationBar({
    required this.page,
    required this.pageSizes,
    required this.onPage,
    required this.onSize,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 2,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Wrap(
          spacing: 12,
          runSpacing: 4,
          alignment: WrapAlignment.end,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('Rows'),
                const SizedBox(width: 6),
                DropdownButton<int>(
                  value: pageSizes.contains(page.size) ? page.size : pageSizes.first,
                  underline: const SizedBox.shrink(),
                  items: pageSizes
                      .map((s) => DropdownMenuItem(value: s, child: Text('$s')))
                      .toList(),
                  onChanged: (s) => s == null ? null : onSize(s),
                ),
              ],
            ),
            Text('${page.firstRowNumber}–${page.lastRowNumber} of ${page.totalElements}'),
            Text('Page ${page.page + 1} of ${page.totalPages}'),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  tooltip: 'First page',
                  icon: const Icon(Icons.first_page),
                  onPressed: page.page == 0 ? null : () => onPage(0),
                ),
                IconButton(
                  tooltip: 'Previous page',
                  icon: const Icon(Icons.chevron_left),
                  // From a page past the end, "previous" means the last real page.
                  onPressed: page.page == 0
                      ? null
                      : () => onPage(page.page - 1 < page.totalPages ? page.page - 1 : page.totalPages - 1),
                ),
                IconButton(
                  tooltip: 'Next page',
                  icon: const Icon(Icons.chevron_right),
                  onPressed:
                      page.page + 1 >= page.totalPages ? null : () => onPage(page.page + 1),
                ),
                IconButton(
                  tooltip: 'Last page',
                  icon: const Icon(Icons.last_page),
                  onPressed: page.page + 1 >= page.totalPages
                      ? null
                      : () => onPage(page.totalPages - 1),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _LoadingState extends StatelessWidget {
  const _LoadingState();
  @override
  Widget build(BuildContext context) => const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 12),
            Text('Loading…'),
          ],
        ),
      );
}

class _EmptyState extends StatelessWidget {
  final String message;
  final bool hasFilters;
  final VoidCallback onClearFilters;
  const _EmptyState({
    required this.message,
    required this.hasFilters,
    required this.onClearFilters,
  });

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.inbox_outlined,
                size: 44, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text(message, style: Theme.of(context).textTheme.titleMedium),
            if (hasFilters) ...[
              const SizedBox(height: 8),
              TextButton(onPressed: onClearFilters, child: const Text('Clear all filters')),
            ],
          ],
        ),
      );
}

class _PastTheEndState extends StatelessWidget {
  final int totalElements;
  final int totalPages;
  final VoidCallback onLastPage;
  const _PastTheEndState({
    required this.totalElements,
    required this.totalPages,
    required this.onLastPage,
  });

  @override
  Widget build(BuildContext context) => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.last_page, size: 44, color: Theme.of(context).colorScheme.outline),
            const SizedBox(height: 12),
            Text('This page is past the end', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text('$totalElements ${totalElements == 1 ? 'row' : 'rows'} on '
                '$totalPages ${totalPages == 1 ? 'page' : 'pages'}'),
            const SizedBox(height: 8),
            TextButton(onPressed: onLastPage, child: const Text('Go to last page')),
          ],
        ),
      );
}

class _ErrorState extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _ErrorState({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 44, color: scheme.error),
            const SizedBox(height: 12),
            Text('Request failed',
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(color: scheme.error)),
            const SizedBox(height: 4),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton.icon(
              icon: const Icon(Icons.refresh),
              label: const Text('Retry'),
              onPressed: onRetry,
            ),
          ],
        ),
      ),
    );
  }
}
