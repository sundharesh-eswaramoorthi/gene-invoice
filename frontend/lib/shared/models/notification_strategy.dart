import 'package:intl/intl.dart';

/// Selectable predicate operators for a notification strategy, mirroring the backend enums
/// exactly: the wire values are the enum names.
const Map<String, String> dateOperatorLabels = {
  'BEFORE': 'Before',
  'ON': 'On',
  'AFTER': 'After',
  'BETWEEN': 'Between',
};

const Map<String, String> amountOperatorLabels = {
  'LESS_THAN': 'Less than',
  'EQUAL': 'Equal to',
  'GREATER_THAN': 'Greater than',
  'BETWEEN': 'Between',
};

class NotificationStrategy {
  final int id;
  final String title;
  final String? description;
  final List<String> statuses;
  final String dateOperator;
  final DateTime dateFrom;
  final DateTime? dateTo;
  final String amountOperator;
  final double amountFrom;
  final double? amountTo;
  final List<int> additionalRecipientUserIds;
  final bool active;

  const NotificationStrategy({
    required this.id,
    required this.title,
    required this.description,
    required this.statuses,
    required this.dateOperator,
    required this.dateFrom,
    required this.dateTo,
    required this.amountOperator,
    required this.amountFrom,
    required this.amountTo,
    required this.additionalRecipientUserIds,
    required this.active,
  });

  factory NotificationStrategy.fromJson(Map<String, dynamic> json) =>
      NotificationStrategy(
        id: (json['id'] as num).toInt(),
        title: json['title'] as String,
        description: json['description'] as String?,
        statuses: ((json['statuses'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        dateOperator: json['dateOperator'] as String,
        dateFrom: DateTime.parse(json['dateFrom'] as String),
        dateTo: json['dateTo'] == null
            ? null
            : DateTime.parse(json['dateTo'] as String),
        amountOperator: json['amountOperator'] as String,
        amountFrom: (json['amountFrom'] as num).toDouble(),
        amountTo: (json['amountTo'] as num?)?.toDouble(),
        additionalRecipientUserIds:
            ((json['additionalRecipientUserIds'] as List?) ?? const [])
                .map((e) => (e as num).toInt())
                .toList(),
        active: json['active'] as bool? ?? true,
      );
}

/// The create/update payload. Title, at least one status, a complete date predicate and a
/// complete amount predicate are required; description is optional.
class StrategyDraft {
  static final DateFormat _day = DateFormat('yyyy-MM-dd');

  final String title;
  final String? description;
  final List<String> statuses;
  final String dateOperator;
  final DateTime dateFrom;
  final DateTime? dateTo;
  final String amountOperator;
  final double amountFrom;
  final double? amountTo;
  final List<int> additionalRecipientUserIds;

  const StrategyDraft({
    required this.title,
    required this.description,
    required this.statuses,
    required this.dateOperator,
    required this.dateFrom,
    required this.dateTo,
    required this.amountOperator,
    required this.amountFrom,
    required this.amountTo,
    required this.additionalRecipientUserIds,
  });

  Map<String, dynamic> toJson() => {
        'title': title,
        if (description != null && description!.isNotEmpty)
          'description': description,
        'statuses': statuses,
        'dateOperator': dateOperator,
        'dateFrom': _day.format(dateFrom),
        if (dateTo != null) 'dateTo': _day.format(dateTo!),
        'amountOperator': amountOperator,
        'amountFrom': amountFrom,
        if (amountTo != null) 'amountTo': amountTo,
        'additionalRecipientUserIds': additionalRecipientUserIds,
      };
}

/// A non-customer account offered as an additional-recipient choice, with its role shown.
class StrategyRecipientOption {
  final int id;
  final String username;
  final String? fullName;
  final String role;

  const StrategyRecipientOption({
    required this.id,
    required this.username,
    required this.fullName,
    required this.role,
  });

  factory StrategyRecipientOption.fromJson(Map<String, dynamic> json) =>
      StrategyRecipientOption(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        fullName: json['fullName'] as String?,
        role: json['role'] as String,
      );
}
