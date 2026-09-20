import '../../core/format.dart';

/// The records an email can be about (E1). The wire value, routes and API paths all follow from
/// the name, so a list page, a details page and the backend agree on one spelling.
enum EmailEntityType {
  customer,
  invoice,
  product,
  payment,
  promise,
  dispute,
  user,
  role;

  /// 'INVOICE', as the API spells it.
  String get wire => name.toUpperCase();

  /// 'invoice', for sentences: "Choose the invoice".
  String get noun => name;

  /// 'invoices'.
  String get plural => '${name}s';

  /// 'Invoice', for labels.
  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  /// '/invoices' — the list page; a record's page is `$routeBase/$id`.
  String get routeBase => '/$plural';

  /// '/api/invoices' — the list endpoint the record picker searches.
  String get apiPath => '/api$routeBase';

  static EmailEntityType? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }

  // ---- the record picker ----------------------------------------------------------

  /// What the search box says it searches.
  String get searchHint => switch (this) {
        customer || product || role => 'Search by name',
        invoice => 'Search by invoice number',
        payment || promise => 'Search by customer name or number',
        dispute => 'Search by reason or number',
        user => 'Search by username',
      };

  /// Newest first for the records that pile up, alphabetical for the ones people name.
  String get pickerSort => switch (this) {
        customer || product || role => 'name,asc',
        invoice => 'invoiceDate,desc',
        payment => 'paidAt,desc',
        promise => 'promisedDate,desc',
        dispute => 'createdAt,desc',
        user => 'username,asc',
      };

  /// The list filters for what was typed. Payments, promises and disputes have no name of their
  /// own and are known by number, so a typed number ("42" or "#42") finds that one.
  List<String> searchFilters(String search) {
    final text = search.trim();
    if (text.isEmpty) return const [];
    final id = int.tryParse(text.replaceFirst('#', '').trim());
    if (id != null && (this == payment || this == promise || this == dispute)) {
      return ['id:eq:$id'];
    }
    final column = switch (this) {
      customer || product || role => 'name',
      invoice => 'invoiceNumber',
      payment || promise => 'customerName',
      dispute => 'reason',
      user => 'username',
    };
    return ['$column:contains:$text'];
  }

  /// How a row from [apiPath] reads in the picker.
  String recordLabel(Map<String, dynamic> row) => switch (this) {
        customer || product || role => '${row['name'] ?? '$label #${row['id']}'}',
        invoice => '${row['invoiceNumber'] ?? 'Invoice #${row['id']}'}',
        user => '${row['username'] ?? 'User #${row['id']}'}',
        payment || promise || dispute => '$label #${row['id']}',
      };

  String? recordSubtitle(Map<String, dynamic> row) {
    final parts = switch (this) {
      customer => [row['email']],
      invoice => [row['customerName'], if (row['total'] != null) formatMoney(row['total'])],
      product => [if (row['price'] != null) formatMoney(row['price'])],
      payment => [
          row['customerName'],
          if (row['amount'] != null) formatMoney(row['amount']),
          if (row['paidAt'] != null) formatDate(row['paidAt']),
        ],
      promise => [
          row['customerName'],
          if (row['amount'] != null) formatMoney(row['amount']),
          if (row['promisedDate'] != null) 'by ${formatDate(row['promisedDate'])}',
        ],
      dispute => [row['customerName'], row['reason']],
      user => [row['fullName'], row['email']],
      role => [row['description']],
    };
    final text = parts.whereType<Object>().map((p) => '$p').where((p) => p.isNotEmpty).join(' · ');
    return text.isEmpty ? null : text;
  }
}
