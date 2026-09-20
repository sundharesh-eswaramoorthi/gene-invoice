import 'package:intl/intl.dart';

/// Money is rendered from the exact decimal string the backend sends, so no float
/// rounding creeps into the display (AC-E3).
final NumberFormat _money = NumberFormat.currency(locale: 'en_IN', symbol: '₹', decimalDigits: 2);

String formatMoney(Object? value) {
  if (value == null) return _money.format(0);
  if (value is num) return _money.format(value);
  final parsed = num.tryParse(value.toString());
  return parsed == null ? value.toString() : _money.format(parsed);
}

/// What a form sends for an optional box: what was typed without the spaces around it, or null
/// when nothing was. A box left empty means the record has no such value, and a record with no
/// value shows the "—" the tables and detail pages use for one; an empty string is a value, and
/// leaves an empty cell instead (CP-15).
String? optionalText(String text) {
  final trimmed = text.trim();
  return trimmed.isEmpty ? null : trimmed;
}

/// Reads an amount the user typed: digits with at most two decimals, the way the backend stores
/// money. Returns null for anything else, so a form can say so before the server does.
double? parseMoneyInput(String text) {
  final t = text.trim();
  if (!RegExp(r'^\d+(\.\d{1,2})?$').hasMatch(t)) return null;
  return double.parse(t);
}

String formatMoneyCompact(Object? value) {
  final parsed = value is num ? value : num.tryParse(value?.toString() ?? '') ?? 0;
  if (parsed.abs() >= 10000000) return '₹${(parsed / 10000000).toStringAsFixed(2)}Cr';
  if (parsed.abs() >= 100000) return '₹${(parsed / 100000).toStringAsFixed(2)}L';
  return formatMoney(parsed);
}

final DateFormat _date = DateFormat('yyyy-MM-dd');
final DateFormat _dateTime = DateFormat('d MMM yyyy, h:mm a');

String formatDate(Object? value) {
  final d = _toDate(value);
  return d == null ? '—' : _date.format(d.toLocal());
}

/// The calendar day a value names, with no time of day and no conversion into the browser's
/// zone. An instant (`…Z`, or any offset, which Dart parses into UTC) gives its **UTC** day, and
/// a zoneless `yyyy-MM-dd` gives the day it spells.
///
/// Invoice days are UTC everywhere in this app: the backend stamps the invoice date as an
/// instant but counts payment terms from its UTC day (`InvoiceDates`, `ZoneOffset.UTC`), and the
/// due date it stores is that plain calendar day. So a date shown beside a due date or a payment
/// term has to be read the same way — `.toLocal()` moves an instant stamped near midnight onto
/// the neighbouring day in every zone but UTC, and Net 30 then reads as 29 or 31 days.
DateTime? utcDay(Object? value) {
  final d = _toDate(value);
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

/// [utcDay] as `yyyy-MM-dd`. Use it for a date the server counted days from or to; true instants
/// that are only ever read as moments — audit stamps and the like — keep [formatDateTime].
String formatUtcDate(Object? value) {
  final d = utcDay(value);
  return d == null ? '—' : _date.format(d);
}

String formatDateTime(Object? value) {
  final d = _toDate(value);
  return d == null ? '—' : _dateTime.format(d.toLocal());
}

DateTime? _toDate(Object? value) {
  if (value == null) return null;
  if (value is DateTime) return value;
  return DateTime.tryParse(value.toString());
}

/// Turns SCREAMING_SNAKE enum names into readable text.
String humanizeEnum(String? raw) {
  if (raw == null || raw.isEmpty) return '—';
  return raw
      .split('_')
      .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1).toLowerCase())
      .join(' ');
}
