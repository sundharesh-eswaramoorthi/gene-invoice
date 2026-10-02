import '../../features/email/email_models.dart';

/// One of the people a promise or dispute is assigned to, as the server resolved the token today
/// (§6.5 `AssigneeDto`). A role token names whoever holds that seat now, so the list can change
/// without anyone editing the record.
class RecordAssignee {
  final EmailToken token;
  final String label;
  final List<AssigneePerson> people;

  /// A role nobody holds any more, or a person who can no longer see the record.
  final bool unresolved;

  const RecordAssignee({
    required this.token,
    required this.label,
    this.people = const [],
    this.unresolved = false,
  });

  factory RecordAssignee.fromJson(Map<String, dynamic> json) => RecordAssignee(
        token: EmailToken.fromJson((json['token'] as Map).cast<String, dynamic>()),
        label: json['label'] as String? ?? '',
        people: ((json['people'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => AssigneePerson.fromJson(m.cast<String, dynamic>()))
            .toList(),
        unresolved: json['unresolved'] as bool? ?? false,
      );

  /// Null when the server sent none — a list or an audit snapshot, which never carry them.
  static List<RecordAssignee>? listFrom(Object? raw) => raw is List
      ? raw
          .whereType<Map>()
          .where((m) => m['token'] is Map)
          .map((m) => RecordAssignee.fromJson(m.cast<String, dynamic>()))
          .toList()
      : null;
}

class AssigneePerson {
  final int userId;
  final String name;
  final bool primary;

  const AssigneePerson({required this.userId, required this.name, this.primary = false});

  factory AssigneePerson.fromJson(Map<String, dynamic> json) => AssigneePerson(
        userId: (json['userId'] as num).toInt(),
        name: json['name'] as String? ?? '',
        primary: json['primary'] as bool? ?? false,
      );
}
