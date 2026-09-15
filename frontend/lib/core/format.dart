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
