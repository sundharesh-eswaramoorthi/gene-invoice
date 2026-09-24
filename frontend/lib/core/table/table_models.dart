import 'package:flutter/foundation.dart';

@immutable
class TableFilter {
  final String field;
  final String operator;
  final List<String> values;

  /// How to show the value in a chip where the raw value means nothing to the reader — a
  /// customer's name rather than "184" (D-50). It is not part of the wire format or of equality:
  /// it is only a label, and a filter restored from a URL falls back to showing the id.
  final String? label;

  const TableFilter(this.field, this.operator, this.values, {this.label});

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

/// The one spelling of an as-of day on the wire, in the URL and in a locked chip: `yyyy-MM-dd`,
/// UTC, no time of day. The server refuses anything else (B3).
String formatAsOfDay(DateTime day) => '${day.year.toString().padLeft(4, '0')}-'
    '${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';

/// A date the client will ask the server for, normalised to a bare UTC day.
///
/// Normalising HERE and not in the constructor is deliberate: [TableQuery] is const, its equality
/// is what the providers refetch on, and two DateTimes an hour apart on the same day would
/// otherwise be two different queries for one answer (B3).
DateTime asOfDay(DateTime raw) => DateTime.utc(raw.year, raw.month, raw.day);

DateTime? parseAsOfDay(String? raw) {
  if (raw == null || raw.isEmpty) return null;
  final parsed = DateTime.tryParse(raw);
  return parsed == null ? null : asOfDay(parsed);
}

@immutable
class TableQuery {
  final int page;
  final int size;
  final String? sort;
  final List<TableFilter> filters;

  /// The day this view is being asked about, or null for "now". Part of the query and not a
  /// separate piece of screen state, so it survives the URL, the back button, a shared link, a
  /// sort, a filter and the summary request — the same reason the region narrowing is a filter
  /// rather than a field (B3).
  final DateTime? asOf;

  const TableQuery(
      {this.page = 0, this.size = 20, this.sort, this.filters = const [], this.asOf});

  bool get isAsOf => asOf != null;

  /// `asOf ?? this.asOf` cannot say "clear it", and clearing it is the whole of "Back to today",
  /// so the parameter takes a sentinel instead of a null default (B3).
  static const Object _keepAsOf = Object();

  TableQuery copyWith({
    int? page,
    int? size,
    String? sort,
    List<TableFilter>? filters,
    Object? asOf = _keepAsOf,
  }) =>
      TableQuery(
        page: page ?? this.page,
        size: size ?? this.size,
        sort: sort ?? this.sort,
        filters: filters ?? this.filters,
        asOf: identical(asOf, _keepAsOf) ? this.asOf : asOf as DateTime?,
      );

  TableQuery withSize(int newSize) => copyWith(page: 0, size: newSize);
  // withSort and withFilters build a TableQuery directly rather than through copyWith, so every
  // field they do not name is DROPPED. asOf has to be carried here by hand or sorting a
  // historical list silently returns it to today (B3).
  TableQuery withSort(String? newSort) =>
      TableQuery(page: 0, size: size, sort: newSort, filters: filters, asOf: asOf);
  TableQuery withFilters(List<TableFilter> newFilters) =>
      TableQuery(page: 0, size: size, sort: sort, filters: newFilters, asOf: asOf);

  /// Entering or leaving the past. Page 0, because the record set is a different one (B3).
  TableQuery withAsOf(DateTime? day) =>
      copyWith(page: 0, asOf: day == null ? null : asOfDay(day));

  TableQuery addFilter(TableFilter f) {
    final next = filters.where((x) => x.field != f.field || x.operator != f.operator).toList()
      ..add(f);
    return withFilters(next);
  }

  TableQuery removeFilter(TableFilter f) =>
      withFilters(filters.where((x) => x != f).toList());

  bool get hasFilters => filters.isNotEmpty;

  Map<String, dynamic> toApiParams() => {
        'page': page,
        'size': size,
        if (sort != null && sort!.isNotEmpty) 'sort': sort,
        if (filters.isNotEmpty) 'filter': filters.map((f) => f.wire).toList(),
        if (asOf != null) 'asOf': formatAsOfDay(asOf!),
      };

  Map<String, dynamic> toRouteParams() => {
        if (page != 0) 'page': '$page',
        'size': '$size',
        if (sort != null && sort!.isNotEmpty) 'sort': sort!,
        if (filters.isNotEmpty) 'f': filters.map((f) => f.wire).toList(),
        // The same name in the URL as on the wire, so a link a reader hand-edits means what it
        // looks like it means — and so "the ageing as of month-end" survives being shared (B3).
        if (asOf != null) 'asOf': formatAsOfDay(asOf!),
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
      // Unparseable is "now", not an error: a mangled link shows today's rows under today's
      // heading, which is the only wrong answer nobody can misread (B3).
      asOf: parseAsOfDay(params['asOf']?.firstOrNull),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is TableQuery &&
      other.page == page &&
      other.size == size &&
      other.sort == sort &&
      // Without this the providers key on an unchanged query and never refetch: picking a date
      // would change the chip and leave today's rows underneath it (B3).
      other.asOf == asOf &&
      listEquals(other.filters, filters);

  @override
  int get hashCode => Object.hash(page, size, sort, asOf, Object.hashAll(filters));
}

/// What a response says about the date it was answered as of — the server's own words, never the
/// client's. Null on a live read, and null on an as-of request the server short-circuited to live
/// because the date was not in the past (B3).
///
/// Hand-written and defensive, the house convention: an unknown key is ignored and a missing one
/// takes a safe default, so a field added to AsOfInfo next quarter cannot break a shipped client.
@immutable
class AsOfInfo {
  /// The UTC day answered, yyyy-MM-dd.
  final String date;

  /// The day the mirror started, or null while nothing has installed one.
  final String? floor;

  /// False when any value in the answer is the floor's value rather than the value of the day.
  final bool exact;

  /// RECONSTRUCTED from interval rows, or SEEDED from the install-time seed rows.
  final String origin;

  /// Always "records": rights, privileges and identity are today's, never then's.
  final String appliesTo;

  /// Records deleted before the floor, which have no mirror row and can never be shown.
  final int omittedDeleted;

  /// One sentence per caveat. Rendered VERBATIM — the client never composes a caveat of its own,
  /// because only the server knows why an answer is approximate (B3).
  final List<String> notes;

  const AsOfInfo({
    required this.date,
    required this.floor,
    required this.exact,
    required this.origin,
    required this.appliesTo,
    required this.omittedDeleted,
    required this.notes,
  });

  static const originSeeded = 'SEEDED';

  static AsOfInfo? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final json = raw.cast<String, dynamic>();
    final date = json['date'];
    if (date is! String || date.isEmpty) return null;
    return AsOfInfo(
      date: date,
      floor: json['floor'] as String?,
      exact: json['exact'] as bool? ?? true,
      origin: json['origin'] as String? ?? 'RECONSTRUCTED',
      appliesTo: json['appliesTo'] as String? ?? 'records',
      omittedDeleted: (json['omittedDeleted'] as num?)?.toInt() ?? 0,
      notes: ((json['notes'] as List?) ?? const []).map((e) => '$e').toList(),
    );
  }

  DateTime? get day => parseAsOfDay(date);

  bool get isSeeded => origin == originSeeded;

  /// Whether this answer needs the loud banner rather than the quiet chip: a value that is the
  /// floor's rather than the day's, or a table that was repaired after the fact (B3).
  bool get isApproximate => !exact || isSeeded;

  bool get hasSomethingToSay => isApproximate || notes.isNotEmpty || omittedDeleted > 0;
}

@immutable
class PagedResult<T> {
  final List<T> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;
  final String? sort;
  final List<String> appliedFilters;

  final List<TableFilter> lockedFilters;

  /// What the SERVER says it answered. Null means it answered live — either nothing was asked, or
  /// a date at or after today was short-circuited — and the difference between "I asked for
  /// January" and "I was given January" is the one the reader has to be able to see (B3).
  final AsOfInfo? asOf;

  const PagedResult({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
    required this.sort,
    required this.appliedFilters,
    required this.lockedFilters,
    this.asOf,
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
        asOf: AsOfInfo.fromJson(json['asOf']),
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
        // Dropped here, every screen that maps a page loses the one thing that says it is not
        // looking at today (B3).
        asOf: asOf,
      );

  bool get isEmpty => content.isEmpty;
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

  /// EXACT — this column is reconstructed as of the date — or CURRENT — it is today's value even
  /// on a historical page, because nothing mirrors what it reads. The editor says so beside the
  /// column name rather than leaving a reader to assume the whole row is from January (B3).
  final String asOfMode;

  const ColumnDef({
    required this.name,
    required this.label,
    required this.type,
    required this.sortable,
    required this.filterable,
    required this.operators,
    required this.enumValues,
    required this.referenceKind,
    this.asOfMode = 'EXACT',
  });

  static const modeCurrent = 'CURRENT';

  bool get isCurrentUnderAsOf => asOfMode == modeCurrent;

  factory ColumnDef.fromJson(Map<String, dynamic> json) => ColumnDef(
        name: json['name'] as String,
        label: json['label'] as String? ?? json['name'] as String,
        type: parseColumnType(json['type'] as String?),
        sortable: json['sortable'] as bool? ?? false,
        filterable: json['filterable'] as bool? ?? false,
        operators: ((json['operators'] as List?) ?? const []).map((e) => e.toString()).toList(),
        enumValues: ((json['enumValues'] as List?) ?? const []).map((e) => e.toString()).toList(),
        referenceKind: json['referenceKind'] as String?,
        // EXACT when the server says nothing, which is also what every column of every table says
        // today: the CURRENT flags live on the unpublished as-of twins and TableSchemaController
        // reads the LIVE ColumnDef. Verified by reading the controller, not assumed (B3).
        asOfMode: json['asOfMode'] as String? ?? 'EXACT',
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

  /// Whether this table has a mirror behind it and can therefore be asked as of a date. The gate
  /// for the date picker: the server decides, the client never guesses from a list of names (B3).
  final bool asOfSupported;

  const TableSchema({
    required this.entity,
    required this.defaultSort,
    required this.pageSizes,
    required this.defaultPageSize,
    required this.datePresets,
    required this.columns,
    this.asOfSupported = false,
  });

  factory TableSchema.fromJson(Map<String, dynamic> json) => TableSchema(
        entity: json['entity'] as String,
        asOfSupported: json['asOfSupported'] as bool? ?? false,
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
