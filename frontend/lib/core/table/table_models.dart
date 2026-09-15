import 'package:flutter/foundation.dart';

/// One filter chip: a column, an operator, and its values.
/// Wire form is `field:operator:value`, matching the backend's parser.
@immutable
class TableFilter {
  final String field;
  final String operator;
  final List<String> values;

  const TableFilter(this.field, this.operator, this.values);

  String get wire => '$field:$operator:${values.join(',')}';

  static TableFilter? parse(String raw) {
    final first = raw.indexOf(':');
    if (first <= 0) return null;
    final second = raw.indexOf(':', first + 1);
    if (second < 0) return null;
    final field = raw.substring(0, first);
    final op = raw.substring(first + 1, second);
    final rest = raw.substring(second + 1);
    final multi = _multiValueOperators.contains(op);
    final values = rest.isEmpty
        ? const <String>[]
        : (multi ? rest.split(',').where((v) => v.isNotEmpty).toList() : [rest]);
    return TableFilter(field, op, values);
  }

  static const _multiValueOperators = {'in', 'notIn', 'between', 'isEmpty', 'isNotEmpty'};

  @override
  bool operator ==(Object other) =>
      other is TableFilter &&
      other.field == field &&
      other.operator == operator &&
      listEquals(other.values, values);

  @override
  int get hashCode => Object.hash(field, operator, Object.hashAll(values));
}

/// Page, size, sort and filters for one list view. Round-trips through the URL so a
/// filtered view can be shared or bookmarked.
@immutable
class TableQuery {
  final int page;
  final int size;
  final String? sort;
  final List<TableFilter> filters;

  const TableQuery({this.page = 0, this.size = 20, this.sort, this.filters = const []});

  TableQuery copyWith({int? page, int? size, String? sort, List<TableFilter>? filters}) =>
      TableQuery(
        page: page ?? this.page,
        size: size ?? this.size,
        sort: sort ?? this.sort,
        filters: filters ?? this.filters,
      );

  /// Changing size, sort or filters always resets to the first page (AC-D2).
  TableQuery withSize(int newSize) => copyWith(page: 0, size: newSize);
  TableQuery withSort(String? newSort) => TableQuery(page: 0, size: size, sort: newSort, filters: filters);
  TableQuery withFilters(List<TableFilter> newFilters) =>
      TableQuery(page: 0, size: size, sort: sort, filters: newFilters);

  TableQuery addFilter(TableFilter f) {
    final next = filters.where((x) => x.field != f.field || x.operator != f.operator).toList()
      ..add(f);
    return withFilters(next);
  }

  TableQuery removeFilter(TableFilter f) =>
      withFilters(filters.where((x) => x != f).toList());

  bool get hasFilters => filters.isNotEmpty;

  /// Query parameters for the API call.
  Map<String, dynamic> toApiParams() => {
        'page': page,
        'size': size,
        if (sort != null && sort!.isNotEmpty) 'sort': sort,
        if (filters.isNotEmpty) 'filter': filters.map((f) => f.wire).toList(),
      };

  /// Query parameters for the browser URL, so the view is restorable (AC-D4).
  Map<String, dynamic> toRouteParams() => {
        if (page != 0) 'page': '$page',
        'size': '$size',
        if (sort != null && sort!.isNotEmpty) 'sort': sort!,
        if (filters.isNotEmpty) 'f': filters.map((f) => f.wire).toList(),
      };

  static TableQuery fromRoute(
    Map<String, List<String>> params, {
    required int defaultSize,
    String? defaultSort,
  }) {
    final size = int.tryParse(params['size']?.firstOrNull ?? '') ?? defaultSize;
    return TableQuery(
      page: int.tryParse(params['page']?.firstOrNull ?? '') ?? 0,
      size: size,
      sort: params['sort']?.firstOrNull ?? defaultSort,
      filters: (params['f'] ?? const [])
          .map(TableFilter.parse)
          .whereType<TableFilter>()
          .toList(),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is TableQuery &&
      other.page == page &&
      other.size == size &&
      other.sort == sort &&
      listEquals(other.filters, filters);

  @override
  int get hashCode => Object.hash(page, size, sort, Object.hashAll(filters));
}

/// The paged envelope every list endpoint returns.
@immutable
class PagedResult<T> {
  final List<T> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final String? sort;
  final List<String> appliedFilters;

  /// Filters the server pinned on regardless of the request — a POC's locked book (AC-A6).
  final List<TableFilter> lockedFilters;

  const PagedResult({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.sort,
    required this.appliedFilters,
    required this.lockedFilters,
  });

  static PagedResult<Map<String, dynamic>> fromJson(Map<String, dynamic> json) => PagedResult(
        content: ((json['content'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .toList(),
        page: (json['page'] as num?)?.toInt() ?? 0,
        size: (json['size'] as num?)?.toInt() ?? 20,
        totalElements: (json['totalElements'] as num?)?.toInt() ?? 0,
        totalPages: (json['totalPages'] as num?)?.toInt() ?? 1,
        sort: json['sort'] as String?,
        appliedFilters:
            ((json['appliedFilters'] as List?) ?? const []).map((e) => e.toString()).toList(),
        lockedFilters: ((json['lockedFilters'] as List?) ?? const [])
            .map((e) => TableFilter.parse(e.toString()))
            .whereType<TableFilter>()
            .toList(),
      );

  PagedResult<R> map<R>(R Function(T) mapper) => PagedResult<R>(
        content: content.map(mapper).toList(),
        page: page,
        size: size,
        totalElements: totalElements,
        totalPages: totalPages,
        sort: sort,
        appliedFilters: appliedFilters,
        lockedFilters: lockedFilters,
      );

  bool get isEmpty => content.isEmpty;
  /// Row numbers for "1–20 of 45"; both are 0 on an empty page, even one past the end.
  int get firstRowNumber => content.isEmpty ? 0 : page * size + 1;
  int get lastRowNumber => content.isEmpty ? 0 : page * size + content.length;
}

enum ColumnType { text, enumeration, boolean, number, money, date, reference, unknown }

ColumnType parseColumnType(String? raw) => switch (raw) {
      'TEXT' => ColumnType.text,
      'ENUM' => ColumnType.enumeration,
      'BOOLEAN' => ColumnType.boolean,
      'NUMBER' => ColumnType.number,
      'MONEY' => ColumnType.money,
      'DATE' => ColumnType.date,
      'REFERENCE' => ColumnType.reference,
      _ => ColumnType.unknown,
    };

/// One column as the backend describes it. The filter UI is built from these, so a
/// column the server cannot handle is never offered (D.4).
@immutable
class ColumnDef {
  final String name;
  final String label;
  final ColumnType type;
  final bool sortable;
  final bool filterable;
  final List<String> operators;
  final List<String> enumValues;
  final String? referenceKind;

  const ColumnDef({
    required this.name,
    required this.label,
    required this.type,
    required this.sortable,
    required this.filterable,
    required this.operators,
    required this.enumValues,
    required this.referenceKind,
  });

  factory ColumnDef.fromJson(Map<String, dynamic> json) => ColumnDef(
        name: json['name'] as String,
        label: json['label'] as String? ?? json['name'] as String,
        type: parseColumnType(json['type'] as String?),
        sortable: json['sortable'] as bool? ?? false,
        filterable: json['filterable'] as bool? ?? false,
        operators: ((json['operators'] as List?) ?? const []).map((e) => e.toString()).toList(),
        enumValues: ((json['enumValues'] as List?) ?? const []).map((e) => e.toString()).toList(),
        referenceKind: json['referenceKind'] as String?,
      );
}

@immutable
class TableSchema {
  final String entity;
  final String? defaultSort;
  final List<int> pageSizes;
  final int defaultPageSize;
  final List<String> datePresets;
  final List<ColumnDef> columns;

  const TableSchema({
    required this.entity,
    required this.defaultSort,
    required this.pageSizes,
    required this.defaultPageSize,
    required this.datePresets,
    required this.columns,
  });

  factory TableSchema.fromJson(Map<String, dynamic> json) => TableSchema(
        entity: json['entity'] as String,
        defaultSort: json['defaultSort'] as String?,
        pageSizes:
            ((json['pageSizes'] as List?) ?? const [10, 20, 50]).map((e) => (e as num).toInt()).toList(),
        defaultPageSize: (json['defaultPageSize'] as num?)?.toInt() ?? 20,
        datePresets: ((json['datePresets'] as List?) ?? const []).map((e) => e.toString()).toList(),
        columns: ((json['columns'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(ColumnDef.fromJson)
            .toList(),
      );

  ColumnDef? column(String name) {
    for (final c in columns) {
      if (c.name == name) return c;
    }
    return null;
  }

  List<ColumnDef> get filterable => columns.where((c) => c.filterable).toList();

  String labelFor(String name) => column(name)?.label ?? name;
}

/// Human-readable operator names for the filter builder.
String operatorLabel(String op) => switch (op) {
      'eq' => 'is',
      'neq' => 'is not',
      'contains' => 'contains',
      'in' => 'is any of',
      'notIn' => 'is none of',
      'gt' => 'greater than',
      'gte' => 'at least',
      'lt' => 'less than',
      'lte' => 'at most',
      'between' => 'between',
      'isEmpty' => 'is empty',
      'isNotEmpty' => 'is not empty',
      'relative' => 'in period',
      _ => op,
    };

String datePresetLabel(String preset) => switch (preset) {
      'today' => 'Today',
      'yesterday' => 'Yesterday',
      'last7Days' => 'Last 7 days',
      'last30Days' => 'Last 30 days',
      'thisMonth' => 'This month',
      'lastMonth' => 'Last month',
      'thisYear' => 'This year',
      'past' => 'In the past',
      'future' => 'In the future',
      _ => preset,
    };
