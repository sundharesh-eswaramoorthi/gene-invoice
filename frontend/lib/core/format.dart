import 'package:intl/intl.dart';

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
final DateFormat _dayLong = DateFormat('d MMM yyyy');

/// A day written for a person to read — "31 Jan 2026" — rather than for a server to parse. The
/// wire spelling of the same day is yyyy-MM-dd and lives beside the query that carries it (B3).
String formatDayLong(DateTime day) => _dayLong.format(DateTime(day.year, day.month, day.day));

String formatDate(Object? value) {
  final d = _toDate(value);
  return d == null ? '—' : _date.format(d.toLocal());
}

DateTime? utcDay(Object? value) {
  final d = _toDate(value);
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

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

String humanizeEnum(String? raw) {
  if (raw == null || raw.isEmpty) return '—';
  return raw
      .split('_')
      .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1).toLowerCase())
      .join(' ');
}
