import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/table/table_models.dart';

void main() {
  group('TableFilter wire format', () {
    test('round-trips a single-value filter', () {
      const f = TableFilter('status', 'eq', ['UNPAID']);
      expect(f.wire, 'status:eq:UNPAID');
      expect(TableFilter.parse(f.wire), f);
    });

    test('round-trips a multi-value filter', () {
      const f = TableFilter('total', 'between', ['100', '500']);
      expect(f.wire, 'total:between:100,500');
      expect(TableFilter.parse(f.wire), f);
    });

    test('keeps commas and colons inside a single-value filter', () {
      const f = TableFilter('customerName', 'contains', ['Smith, Jones & Co: Ltd']);
      final parsed = TableFilter.parse(f.wire);
      expect(parsed, f);
      expect(parsed!.values.single, 'Smith, Jones & Co: Ltd');
    });

    test('round-trips a valueless filter', () {
      const f = TableFilter('salesPocUserId', 'isEmpty', []);
      expect(f.wire, 'salesPocUserId:isEmpty:');
      expect(TableFilter.parse(f.wire), f);
    });

    test('rejects malformed input rather than guessing', () {
      expect(TableFilter.parse('nonsense'), isNull);
      expect(TableFilter.parse('field:only'), isNull);
      expect(TableFilter.parse(':eq:x'), isNull);
    });
  });

  group('TableQuery URL round-trip (AC-D4)', () {
    test('restores page, size, sort and filters from route parameters', () {
      const original = TableQuery(
        page: 2,
        size: 50,
        sort: 'total,desc',
        filters: [
          TableFilter('status', 'in', ['UNPAID', 'PARTIALLY_PAID']),
          TableFilter('salesPocUserId', 'eq', ['7']),
        ],
      );

      final params = original.toRouteParams().map((k, v) =>
          MapEntry(k, v is List ? v.cast<String>() : <String>[v as String]));
      final restored = TableQuery.fromRoute(params, defaultSize: 20);

      expect(restored, original);
    });

    test('falls back to the given defaults when the URL says nothing', () {
      final restored =
          TableQuery.fromRoute({}, defaultSize: 10, defaultSort: 'name,asc');
      expect(restored.page, 0);
      expect(restored.size, 10);
      expect(restored.sort, 'name,asc');
      expect(restored.filters, isEmpty);
    });
  });

  group('TableQuery mutations reset to page 1 (AC-D2)', () {
    const onPageThree = TableQuery(page: 3, size: 20, sort: 'total,asc');

    test('changing the page size', () {
      expect(onPageThree.withSize(50).page, 0);
      expect(onPageThree.withSize(50).size, 50);
    });

    test('changing the sort', () {
      expect(onPageThree.withSort('total,desc').page, 0);
    });

    test('adding a filter', () {
      final next = onPageThree.addFilter(const TableFilter('status', 'eq', ['UNPAID']));
      expect(next.page, 0);
      expect(next.filters, hasLength(1));
    });

    test('adding a filter on a column replaces the same condition rather than stacking', () {
      final q = onPageThree
          .addFilter(const TableFilter('status', 'eq', ['UNPAID']))
          .addFilter(const TableFilter('status', 'eq', ['FULLY_PAID']));
      expect(q.filters, hasLength(1));
      expect(q.filters.single.values.single, 'FULLY_PAID');
    });

    test('removing a filter leaves the others alone', () {
      const a = TableFilter('status', 'eq', ['UNPAID']);
      const b = TableFilter('total', 'gt', ['100']);
      final q = onPageThree.addFilter(a).addFilter(b).removeFilter(a);
      expect(q.filters, [b]);
    });
  });

  group('API parameters', () {
    test('sends filters as a repeated parameter', () {
      const q = TableQuery(page: 1, size: 20, sort: 'paidAt,desc', filters: [
        TableFilter('status', 'eq', ['ACTIVE']),
        TableFilter('amount', 'between', ['10', '20']),
      ]);
      final params = q.toApiParams();
      expect(params['page'], 1);
      expect(params['size'], 20);
      expect(params['sort'], 'paidAt,desc');
      expect(params['filter'], ['status:eq:ACTIVE', 'amount:between:10,20']);
    });

    test('omits an empty sort and empty filters', () {
      const q = TableQuery();
      expect(q.toApiParams().containsKey('sort'), isFalse);
      expect(q.toApiParams().containsKey('filter'), isFalse);
    });
  });

  group('PagedResult', () {
    test('reads the envelope including the locked scope chips', () {
      final page = PagedResult.fromJson(const {
        'content': [
          {'id': 1}
        ],
        'page': 1,
        'size': 20,
        'totalElements': 45,
        'totalPages': 3,
        'sort': 'invoiceDate,desc',
        'appliedFilters': ['status:eq:UNPAID'],
        'lockedFilters': ['salesPocUserId:eq:12'],
      });

      expect(page.totalElements, 45);
      expect(page.firstRowNumber, 21);
      expect(page.lastRowNumber, 21);
      expect(page.lockedFilters.single,
          const TableFilter('salesPocUserId', 'eq', ['12']));
    });

    test('an empty page reports row zero rather than a negative range', () {
      final page = PagedResult.fromJson(const {
        'content': [],
        'page': 0,
        'size': 20,
        'totalElements': 0,
        'totalPages': 1,
      });
      expect(page.isEmpty, isTrue);
      expect(page.firstRowNumber, 0);
      expect(page.lastRowNumber, 0);
    });
  });

  group('TableSchema', () {
    final schema = TableSchema.fromJson(const {
      'entity': 'invoices',
      'defaultSort': 'invoiceDate,desc',
      'pageSizes': [10, 20, 50],
      'defaultPageSize': 20,
      'datePresets': ['today', 'last7Days'],
      'columns': [
        {
          'name': 'total',
          'label': 'Total',
          'type': 'MONEY',
          'sortable': true,
          'filterable': true,
          'operators': ['eq', 'between'],
          'enumValues': [],
        },
        {
          'name': 'notes',
          'label': 'Notes',
          'type': 'TEXT',
          'sortable': false,
          'filterable': true,
          'operators': ['contains'],
          'enumValues': [],
        },
      ],
    });

    test('exposes each column with its type and operators', () {
      expect(schema.column('total')!.type, ColumnType.money);
      expect(schema.column('total')!.operators, ['eq', 'between']);
      expect(schema.column('notes')!.sortable, isFalse);
    });

    test('labels a column, falling back to its name', () {
      expect(schema.labelFor('total'), 'Total');
      expect(schema.labelFor('unknown'), 'unknown');
    });

    test('offers only filterable columns to the filter builder', () {
      expect(schema.filterable.map((c) => c.name), ['total', 'notes']);
    });
  });
}
