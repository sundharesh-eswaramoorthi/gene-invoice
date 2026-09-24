import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/auth_controller.dart';
import '../api/api_client.dart';
import '../region/region_providers.dart';
import 'as_of_bar.dart';
import 'filter_editor.dart';
import 'table_models.dart';
import 'table_providers.dart';

class TableColumnSpec<T> {
  final String label;
  final String? sortKey;
  final bool numeric;

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

class BulkActionSpec {
  final String action;
  final String label;
  final IconData icon;
  final bool destructive;

  final Future<Map<String, dynamic>?> Function(BuildContext context)? buildParams;

  final String? endpoint;

  final String Function(int succeeded)? successMessage;

  const BulkActionSpec({
    required this.action,
    required this.label,
    required this.icon,
    this.destructive = false,
    this.buildParams,
    this.endpoint,
    this.successMessage,
  });
}

class QuickFilterSpec {
  final String label;
  final IconData? icon;
  final TableFilter filter;

  const QuickFilterSpec({required this.label, required this.filter, this.icon});
}

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

  final List<QuickFilterSpec> quickFilters;

  /// Called after a bulk action has run, for anything outside the table that its rows feed —
  /// the bell's unread badge after "Mark read", say (D-55).
  final VoidCallback? onBulkDone;
  final bool canExport;

  final bool selectable;

  final String emptyMessage;
  final Widget? header;

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
    this.onBulkDone,
    this.extraParams = const {},
    this.onRowTap,
    this.rowActions,
    this.mobileCard,
    this.tiles,
    this.bulkActions = const [],
    this.quickFilters = const [],
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

  /// THE DATE THIS TABLE IS READ-ONLY FOR, which is not simply the date that was asked for.
  ///
  /// `AsOfDates.parse` short-circuits any date at or after today to live, and the picker's own
  /// last selectable day IS today — so "pick today and press Show" asks for a date and is answered
  /// with today's live rows. Driving the dimming off `widget.query.asOf` then dimmed every write
  /// on the page, with a tooltip saying the reader was looking at the past, beside a chip
  /// correctly saying they were looking at today; and the writes would in fact have succeeded,
  /// because nothing the buttons send carries the date.
  ///
  /// So this reads the SAME fact AsOfBar reads when it decides what the chip says: the SERVER's
  /// account of what it answered. One fact, one screen. Until an answer arrives — or when the
  /// request failed — the date that was asked for still stands, because the safe reading is the
  /// one that must be on screen first (B3).
  DateTime? get _readOnlyAsOf {
    final asked = widget.query.asOf;
    if (asked == null) return null;
    final answer = ref.read(tablePageProvider(_request));
    final servedLive = answer.hasValue && answer.valueOrNull?.asOf == null;
    return servedLive ? null : asked;
  }

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

  /// Whether a row reads as selected. With the whole filtered set selected that is every row,
  /// so the ticks on screen agree with the count the toolbar states (TBL-06).
  bool _isSelected(T row) => _selectAllMatching || _selected.contains(widget.idOf(row));

  /// Ticking or unticking one row. While the whole filtered set is selected every row is ticked,
  /// so unticking one has to mean something: the selection drops back to the rows on this page
  /// less that one, and the toolbar's count follows — rather than silently reverting to whichever
  /// rows happened to be ticked before "Select all" (TBL-06). Unticking the heading's checkbox
  /// arrives here as one call per row and so ends with nothing selected, as it reads.
  void _toggleRow(List<T> pageRows, T row, bool on) {
    setState(() {
      if (_selectAllMatching) {
        _selectAllMatching = false;
        _selected
          ..clear()
          ..addAll(pageRows.map(widget.idOf));
      }
      if (on) {
        _selected.add(widget.idOf(row));
      } else {
        _selected.remove(widget.idOf(row));
      }
    });
  }

  void _refresh() {
    ref.invalidate(tablePageProvider(_request));
    ref.invalidate(tableSummaryProvider(_request.forSummary));
    _clearSelection();
  }

  @override
  Widget build(BuildContext context) {
    final pageAsync = ref.watch(tablePageProvider(_request));
    final schemaAsync = ref.watch(tableSchemaProvider(widget.entity));
    final schema = schemaAsync.valueOrNull;
    final isNarrow = MediaQuery.sizeOf(context).width < 760;

    // The SERVER's account of what it answered, never the client's guess. Null with a date set
    // means the server served live, which the bar says out loud rather than papering over (B3).
    final asOfInfo = pageAsync.valueOrNull?.asOf;
    // NOT `widget.query.asOf`: the bar below is handed what the client ASKED for, and the write
    // affordances are handed what the server ANSWERED. See _readOnlyAsOf (B3).
    final readOnlyAsOf = _readOnlyAsOf;

    final filterBar = _FilterBar(
      schema: schema,
      query: widget.query,
      quickFilters: widget.quickFilters,
      lockedFilters: pageAsync.valueOrNull?.lockedFilters ?? const [],
      asOfInfo: asOfInfo,
      asOfAnswered: pageAsync.hasValue,
      onQueryChanged: (q) => widget.onQueryChanged(q),
    );
    final pageActions = _ReadOnlyWhileAsOf(
      asOf: readOnlyAsOf,
      child: Theme(
        data: Theme.of(context).copyWith(
          filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(minimumSize: const Size(64, 40))
                .merge(Theme.of(context).filledButtonTheme.style),
          ),
        ),
        child: Wrap(spacing: 8, runSpacing: 8, children: widget.actions),
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.header != null) widget.header!,
        // ABOVE the tiles, not below them: a caveat saying a value is the floor's rather than the
        // day's has to be read before the numbers it is about, and the tiles are the most
        // prominent numbers on the page (B3).
        AsOfNotice(info: asOfInfo),
        // On a phone the tiles scroll with the rows instead of standing above them, where they
        // left barely one card's worth of list (D-61).
        if (widget.tiles != null && !isNarrow)
          _SummaryTiles(request: _request, builder: widget.tiles!),
        if (isNarrow) ...[
          filterBar,
          if (widget.actions.isNotEmpty)
            Padding(padding: const EdgeInsets.fromLTRB(12, 0, 12, 8), child: pageActions),
        ] else
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: filterBar),
              if (widget.actions.isNotEmpty)
                Padding(padding: const EdgeInsets.fromLTRB(0, 8, 12, 8), child: pageActions),
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
            asOf: readOnlyAsOf,
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
                child: isNarrow
                    ? _cardList(rows,
                        header: widget.tiles == null
                            ? null
                            : _SummaryTiles(request: _request, builder: widget.tiles!))
                    : _dataTable(rows, schema),
              );
            },
          ),
        ),
        // The bar outlives a failed request, because the Rows selector depends on the query
        // alone: a size the server refuses — a hand-edited or stale link — is then fixable on the
        // page, where Retry could only ask the same impossible question again (TBL-09). What
        // describes the answer, the row count and the page arrows, waits for one.
        _PaginationBar(
          page: pageAsync.valueOrNull,
          size: widget.query.size,
          pageSizes: schema?.pageSizes ?? const [10, 20, 50],
          onPage: (p) => widget.onQueryChanged(widget.query.copyWith(page: p)),
          onSize: (s) {
            ref.read(pageSizeStoreProvider.notifier).write(widget.entity, s);
            widget.onQueryChanged(widget.query.withSize(s));
          },
        ),
      ],
    );
  }

  Widget _dataTable(List<T> rows, TableSchema? schema) {
    final sortField = widget.query.sort?.split(',').first;
    final ascending = !(widget.query.sort?.endsWith('desc') ?? false);
    final sortIndex = sortField == null
        ? null
        : widget.columns.indexWhere((c) => c.sortKey == sortField);

    final table = DataTable(
      columnSpacing: 24,
      showCheckboxColumn: _selectable,
      sortColumnIndex: (sortIndex != null && sortIndex >= 0) ? sortIndex : null,
      sortAscending: ascending,
      columns: [
        for (final c in widget.columns)
          DataColumn(
            label: _capped(c, Text(c.label, style: const TextStyle(fontWeight: FontWeight.w600))),
            numeric: c.numeric,
            onSort: c.sortKey == null
                ? null
                : (index, asc) => widget.onQueryChanged(
                    widget.query.withSort('${c.sortKey},${asc ? 'asc' : 'desc'}')),
          ),
      ],
      rows: [
        for (final row in rows)
          DataRow(
            selected: _isSelected(row),
            onSelectChanged: _selectable ? (on) => _toggleRow(rows, row, on == true) : null,
            cells: [
              for (final c in widget.columns)
                DataCell(
                  _capped(c, c.cell(context, row)),
                  onTap: widget.onRowTap == null ? null : () => widget.onRowTap!(context, row),
                ),
            ],
          ),
      ],
    );

    return Scrollbar(
      controller: _hScroll,
      thumbVisibility: true,
      notificationPredicate: (n) => n.depth == 1,
      child: SingleChildScrollView(
        scrollDirection: Axis.vertical,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                  controller: _hScroll,
                  scrollDirection: Axis.horizontal,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(minWidth: constraints.maxWidth),
                    child: table,
                  ),
                ),
              ),
            ),
            if (widget.rowActions != null) _rowActionsTable(rows),
          ],
        ),
      ),
    );
  }

  Widget _rowActionsTable(List<T> rows) => DataTable(
        horizontalMargin: 12,
        showCheckboxColumn: false,
        columns: const [DataColumn(label: SizedBox.shrink())],
        rows: [
          for (final row in rows)
            DataRow(
              selected: _isSelected(row),
              cells: [
                DataCell(_ReadOnlyWhileAsOf(
                  asOf: _readOnlyAsOf,
                  child: IconButtonTheme(
                    data: IconButtonThemeData(
                      style: IconButton.styleFrom(visualDensity: VisualDensity.compact)
                          .merge(IconButtonTheme.of(context).style),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: widget.rowActions!(context, row),
                    ),
                  ),
                )),
              ],
            ),
        ],
      );

  Widget _capped(TableColumnSpec<T> c, Widget child) => c.maxWidth == null
      ? child
      : ConstrainedBox(constraints: BoxConstraints(maxWidth: c.maxWidth!), child: child);

  /// [header] scrolls above the first card — the summary tiles on a phone (D-61).
  Widget _cardList(List<T> rows, {Widget? header}) {
    final lead = header == null ? 0 : 1;
    return ListView.separated(
      padding: const EdgeInsets.all(8),
      itemCount: rows.length + lead,
      separatorBuilder: (_, __) => const SizedBox(height: 6),
      itemBuilder: (context, i) {
        if (header != null && i == 0) return header;
        final row = rows[i - lead];
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
                      value: _isSelected(row),
                      onChanged: (on) => _toggleRow(rows, row, on == true),
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
                    _ReadOnlyWhileAsOf(
                      asOf: _readOnlyAsOf,
                      child: Column(children: widget.rowActions!(context, row)),
                    ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

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
      message: 'This will apply "${spec.label}" to $count record${count == 1 ? '' : 's'}.'
          '${spec.destructive ? '\n\nThis cannot be undone.' : ''}',
      destructive: spec.destructive,
    );
    if (confirmed != true || !context.mounted) return;

    setState(() => _busy = true);
    try {
      final res = await ref.read(dioProvider).post(spec.endpoint ?? '${widget.path}/bulk', data: {
        'action': spec.action,
        if (!_selectAllMatching) 'ids': _selected.toList(),
        if (_selectAllMatching) 'selectAllMatchingFilter': true,
        'sort': widget.query.sort,
        'filters': widget.query.filters.map((f) => f.wire).toList(),
        if (params != null) 'params': params,
      });
      if (mounted) {
        _showBulkResult((res.data as Map).cast<String, dynamic>(), spec);
        _refresh();
        widget.onBulkDone?.call();
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
      final asOf = widget.query.asOf;
      final res = await ref.read(dioProvider).post(
        '${widget.path}/export',
        // An export is the one POST that may carry a date, and it has to carry it as a QUERY
        // parameter: the interceptor reads req.getParameter and never the body. Without this the
        // download is today's rows under a historical list — the same file, silently different
        // from what is on screen, and with none of the caveat row the server writes when it knows
        // (B3).
        queryParameters: {if (asOf != null) 'asOf': formatAsOfDay(asOf)},
        data: {
          'action': 'EXPORT',
          if (!_selectAllMatching) 'ids': _selected.toList(),
          if (_selectAllMatching) 'selectAllMatchingFilter': true,
          'sort': widget.query.sort,
          'filters': widget.query.filters.map((f) => f.wire).toList(),
        },
      );
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

  void _showBulkResult(Map<String, dynamic> result, BulkActionSpec spec) {
    final succeeded = ((result['succeeded'] as List?) ?? const []).length;
    final failed = ((result['failed'] as List?) ?? const []).cast<Map<String, dynamic>>();
    final skipped = ((result['skipped'] as List?) ?? const []).cast<Map<String, dynamic>>();
    // Rows the server HELD for approval: neither a success nor a failure nor a row that did not
    // qualify. Without this bucket a run where every row was held short-circuits below to
    // "0 records updated" and the operator is never told that fifty changes are now waiting (B2).
    final pending = ((result['pending'] as List?) ?? const []).cast<Map<String, dynamic>>();
    final pendingLimitReached = result['pendingLimitReached'] as bool? ?? false;
    final truncated = result['truncated'] as bool? ?? false;

    if (failed.isEmpty && skipped.isEmpty && pending.isEmpty && !truncated) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(spec.successMessage?.call(succeeded) ??
              '$succeeded record${succeeded == 1 ? '' : 's'} updated')));
      return;
    }

    showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('${spec.label} result'),
        content: SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('$succeeded succeeded, ${pending.length} sent for approval, '
                    '${failed.length} failed, ${skipped.length} skipped, '
                    'of ${result['requested']} requested.'),
                if (pendingLimitReached)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'This run had already raised as many approvals as it may at once, so the '
                      'rest were left alone. Run it again for them.',
                      style: TextStyle(color: Theme.of(context).colorScheme.tertiary),
                    ),
                  ),
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
                // Above Skipped and in the app's amber: these rows have not failed and have
                // not happened either, and the reason names the change each one raised (B2).
                if (pending.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text('Sent for approval',
                      style: Theme.of(context)
                          .textTheme
                          .titleSmall
                          ?.copyWith(color: Theme.of(context).colorScheme.tertiary)),
                  for (final p in pending) Text('#${p['id']} — ${p['reason']}'),
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

class _SummaryTiles extends ConsumerWidget {
  final TableRequest request;
  final Widget Function(BuildContext, Map<String, dynamic>) builder;
  const _SummaryTiles({required this.request, required this.builder});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(tableSummaryProvider(request.forSummary));
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      child: async.when(
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

class SummaryTile extends StatelessWidget {
  final String label;
  final String value;
  final IconData? icon;
  final Color? accent;

  static const double width = 170;

  static const double gap = 12;

  const SummaryTile({
    super.key,
    required this.label,
    required this.value,
    this.icon,
    this.accent,
  });

  /// A tile's width in a row [available] wide. Two fixed-width tiles need 352px and a phone's
  /// list has ~350 to give, which dropped every tile onto a row of its own and buried the rows
  /// under a screen and a half of them; below that the pair shares the row instead (UI-07).
  static double widthFor(double available) =>
      available.isFinite && available < width * 2 + gap ? (available - gap) / 2 : width;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return LayoutBuilder(builder: (context, constraints) {
      return Container(
        width: widthFor(constraints.maxWidth),
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
    });
  }
}

/// The wire name of the as-of narrowing, identical on the API, in a locked chip and in the URL.
/// It is NOT a TableFilter in the query — it is [TableQuery.asOf] — which is exactly why "Clear
/// all" keeps it without being told to (B3).
const asOfFilterField = 'asOf';

/// A write affordance while a past date is set.
///
/// It is not hidden, because a button that vanishes teaches nobody anything: it is dimmed and it
/// says why. The refusal itself is the server's — a non-GET carrying ?asOf is 400 "The past is
/// read only" — and this is the client agreeing with it rather than enforcing it (B3).
class _ReadOnlyWhileAsOf extends StatelessWidget {
  final DateTime? asOf;
  final Widget child;

  const _ReadOnlyWhileAsOf({required this.asOf, required this.child});

  @override
  Widget build(BuildContext context) {
    final day = asOf;
    if (day == null) return child;
    return Tooltip(
      message: AsOfBar.readOnlyTooltip(day),
      child: Opacity(
        opacity: 0.45,
        child: IgnorePointer(child: child),
      ),
    );
  }
}

class _FilterBar extends ConsumerWidget {
  final TableSchema? schema;
  final TableQuery query;
  final List<QuickFilterSpec> quickFilters;
  final List<TableFilter> lockedFilters;
  final AsOfInfo? asOfInfo;
  final bool asOfAnswered;
  final ValueChanged<TableQuery> onQueryChanged;

  const _FilterBar({
    required this.schema,
    required this.query,
    required this.quickFilters,
    required this.lockedFilters,
    required this.asOfInfo,
    required this.asOfAnswered,
    required this.onQueryChanged,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final quick = schema == null
        ? const <QuickFilterSpec>[]
        : quickFilters.where((q) => schema!.column(q.filter.field)?.filterable ?? false).toList();
    final regionNames = ref.watch(regionNamesProvider);
    // The branches every picker this bar opens should ask about. A filter has no record to take
    // one from, so the list names its own (B1).
    final branches = pickerRegionsFor(ref.watch(currentUserProvider),
        applied: query.filters, locked: lockedFilters);
    // A region narrowing is a real filter inside the query — so it survives the URL, the back
    // button and a shared link — but it is shown by the selector and not as one more removable
    // chip, and "Clear all" leaves it alone (B1).
    //
    // By OPERATOR and not by field: the selector only ever writes and reads `regionId:in`, so a
    // `regionId:eq:7` built through "Add filter" — which is what the dialog composes by default,
    // REFERENCE's first operator being `eq` — belongs in the chip row like any other filter.
    // Excluding it by field alone applied it, hid it, left it out of the count and kept it
    // through "Clear all" (B1).
    final others = query.filters
        .where((f) => !quick.any((q) => q.filter == f) && !isRegionSelectorFilter(f))
        .toList();
    // The server sends "asOf:eq:2026-01-31" among the locked chips. It is dropped from the chip
    // row and not relabelled, because the bar beside it already says the same thing louder AND
    // offers the way back — and because the fallback label for an unrecognised locked chip is
    // "My records only", which would be a flat lie about the date (B3).
    final visibleLocked = lockedFilters.where((f) => f.field != asOfFilterField).toList();
    // Offered where there is a mirror behind the table, and shown UNCONDITIONALLY once a date is
    // set: a schema that fails to load, or a table whose flag says no while a hand-edited link
    // carries a date, must not leave historical rows on screen with nothing to say so (B3).
    final asOfOffered = (schema?.asOfSupported ?? false) || query.asOf != null;

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          _RegionSelector(query: query, onQueryChanged: onQueryChanged),
          if (asOfOffered)
            AsOfBar(
              value: query.asOf,
              info: asOfInfo,
              answered: asOfAnswered,
              onChanged: (day) => onQueryChanged(query.withAsOf(day)),
            ),
          ActionChip(
            avatar: const Icon(Icons.filter_alt_outlined, size: 18),
            label: const Text('Add filter'),
            onPressed: schema == null
                ? null
                : () async {
                    final f = await showFilterEditor(
                        context: context,
                        schema: schema!,
                        regionIds: branches,
                        asOf: query.asOf);
                    if (f != null) onQueryChanged(query.addFilter(f));
                  },
          ),
          for (final q in quick)
            FilterChip(
              avatar: q.icon == null ? null : Icon(q.icon, size: 18),
              label: Text(q.label),
              selected: query.filters.contains(q.filter),
              onSelected: (on) => onQueryChanged(
                  on ? query.addFilter(q.filter) : query.removeFilter(q.filter)),
            ),
          for (final locked in visibleLocked)
            Tooltip(
              message: locked.field == regionFilterField
                  ? 'This list covers the branches you work in'
                  : 'Your role limits this list to your own records',
              child: Chip(
                avatar: const Icon(Icons.lock_outline, size: 16),
                label: Text(_lockedLabel(locked, regionNames)),
              ),
            ),
          for (final f in others)
            InputChip(
              label: Text(describeFilter(f, schema, asOf: query.asOf != null)),
              onPressed: schema == null
                  ? null
                  : () async {
                      final edited = await showFilterEditor(
                          context: context,
                          schema: schema!,
                          existing: f,
                          regionIds: branches,
                          asOf: query.asOf);
                      if (edited != null) {
                        onQueryChanged(query.removeFilter(f).addFilter(edited));
                      }
                    },
              onDeleted: () => onQueryChanged(query.removeFilter(f)),
            ),
          if (others.isNotEmpty)
            TextButton(
              // The region narrowing is kept: it says which branches this list is about, not
              // which rows within them, and clearing the filters must not silently widen the
              // list back to every branch the caller can see (B1).
              onPressed: () => onQueryChanged(
                  query.withFilters(query.filters.where(isRegionSelectorFilter).toList())),
              child: const Text('Clear all'),
            ),
        ],
      ),
    );
  }

  String _lockedLabel(TableFilter f, Map<int, String> regionNames) {
    if (f.field == 'myBook') return 'My book only';
    if (f.field == regionFilterField) {
      // A person who holds no grant at all gets a chip naming no branch and a page of zero
      // rows, which is a 200 and not an error — so the list has to explain itself (B1).
      if (f.operator == 'isEmpty') return 'No region access — ask an administrator';
      final names = f.values
          .map((v) => regionNames[int.tryParse(v) ?? -1] ?? v)
          .toList();
      return 'Regions: ${names.join(", ")}';
    }
    return 'My records only';
  }
}

/// Which branches this list covers, written into the query as a real `regionId:in:3,7` filter.
///
/// No new query parameter, no new response field and no TableQuery change: the narrowing is the
/// existing filter grammar, so it survives the URL, the back button and a shared link, and every
/// bulk action and export inherits it for free (B1).
class _RegionSelector extends ConsumerWidget {
  final TableQuery query;
  final ValueChanged<TableQuery> onQueryChanged;

  const _RegionSelector({required this.query, required this.onQueryChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final choices = ref.watch(selectableRegionsProvider).valueOrNull ?? const [];
    // One branch, or none, is not a choice: the locked chip already says which branch this list
    // covers, and a picker with a single entry would only invite a click that changes nothing.
    if (choices.length < 2) return const SizedBox.shrink();
    final selected = regionIdsIn(query.filters).toSet();
    final label = selected.isEmpty
        ? 'All my branches'
        : 'Regions: ${choices.where((r) => selected.contains(r.id)).map((r) => r.code).join(", ")}';

    return PopupMenuButton<int>(
      tooltip: 'Which branches this list covers',
      position: PopupMenuPosition.under,
      onSelected: (id) => onQueryChanged(_toggled(selected, id)),
      itemBuilder: (_) => [
        // 0 and not null for "all": PopupMenuButton reads a null selection as a cancelled menu
        // and never calls onSelected, so the entry that clears the narrowing would do nothing.
        CheckedPopupMenuItem<int>(
          value: _allBranches,
          checked: selected.isEmpty,
          child: const Text('All my branches'),
        ),
        const PopupMenuDivider(),
        for (final r in choices)
          CheckedPopupMenuItem<int>(
            value: r.id,
            checked: selected.contains(r.id),
            child: Text(r.label),
          ),
      ],
      child: Chip(
        avatar: const Icon(Icons.account_tree_outlined, size: 16),
        label: Text(label),
        deleteIcon: const Icon(Icons.arrow_drop_down, size: 18),
        onDeleted: null,
      ),
    );
  }

  /// The sentinel the "All my branches" entry carries. No branch has id 0.
  static const _allBranches = 0;

  TableQuery _toggled(Set<int> selected, int id) {
    final next = {...selected};
    if (id == _allBranches) {
      next.clear();
    } else if (!next.remove(id)) {
      next.add(id);
    }
    // Only the selector's own filter is replaced. A `regionId:eq:7` the operator built through
    // "Add filter" is theirs, is on screen as its own chip, and is not this control's to drop
    // silently when they pick a branch (B1).
    final without = query.filters.where((f) => !isRegionSelectorFilter(f)).toList();
    // Nothing selected is "every branch I can see", which is what the mandatory predicate
    // already answers — so it is the absence of a filter rather than a filter naming them all.
    if (next.isEmpty) return query.withFilters(without);
    final ids = next.toList()..sort();
    return query.withFilters([
      ...without,
      TableFilter(regionFilterField, 'in', ids.map((i) => '$i').toList()),
    ]);
  }
}

class _SelectionToolbar extends StatelessWidget {
  final int selectedCount;
  final int totalMatching;
  final bool selectAllMatching;
  final bool busy;
  final List<BulkActionSpec> actions;
  final bool canExport;

  /// The date this selection was made as of, or null. Every bulk action is a WRITE and the server
  /// refuses one carrying ?asOf; the export is a read and stays offered, with the as-of caveat
  /// row the server puts in the file itself (B3).
  final DateTime? asOf;
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
    required this.asOf,
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
              _ReadOnlyWhileAsOf(
                asOf: asOf,
                child: TextButton.icon(
                  icon: Icon(a.icon, size: 18),
                  label: Text(a.label),
                  style: a.destructive
                      ? TextButton.styleFrom(foregroundColor: scheme.error)
                      : null,
                  onPressed: busy ? null : () => onRun(a),
                ),
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
  final PagedResult<Map<String, dynamic>>? page;

  final int size;
  final List<int> pageSizes;
  final ValueChanged<int> onPage;
  final ValueChanged<int> onSize;

  const _PaginationBar({
    required this.page,
    required this.size,
    required this.pageSizes,
    required this.onPage,
    required this.onSize,
  });

  @override
  Widget build(BuildContext context) {
    // On a phone the pager keeps only what the list cannot show by itself — which page this is,
    // and how to move — so the rows get that height back (D-61).
    final compact = MediaQuery.sizeOf(context).width < 760;
    final page = this.page;
    if (compact && page == null) return const SizedBox.shrink();
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
            if (!compact)
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('Rows'),
                  const SizedBox(width: 6),
                  DropdownButton<int>(
                    value: pageSizes.contains(size) ? size : pageSizes.first,
                    underline: const SizedBox.shrink(),
                    items: pageSizes
                        .map((s) => DropdownMenuItem(value: s, child: Text('$s')))
                        .toList(),
                    onChanged: (s) => s == null ? null : onSize(s),
                  ),
                ],
              ),
            if (!compact && page != null)
              Text('${page.firstRowNumber}–${page.lastRowNumber} of ${page.totalElements}'),
            if (page != null) ...[
              // Past the last page there is no current page to name (D-69).
              Text(page.totalPages > 0 && page.page >= page.totalPages
                  ? '${page.totalPages} pages'
                  : 'Page ${page.page + 1} of ${page.totalPages}'),
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
