import 'package:intl/intl.dart';

import '../../shared/models/record_assignee.dart';
import '../email/email_entity.dart';

/// Where a task stands (A6). The wire names are the server's; an unknown one reads as OPEN, like
/// the other status parsers (G25).
enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }

TaskStatus parseTaskStatus(Object? raw) => TaskStatus.values.asNameMap()[raw] ?? TaskStatus.OPEN;

String taskStatusLabel(TaskStatus s) => switch (s) {
      TaskStatus.OPEN => 'Open',
      TaskStatus.IN_PROGRESS => 'In progress',
      TaskStatus.DONE => 'Done',
      TaskStatus.CANCELLED => 'Cancelled',
    };

/// Still to be done: what "My tasks" counts (§5.9.5).
bool taskIsOpen(TaskStatus s) => s == TaskStatus.OPEN || s == TaskStatus.IN_PROGRESS;

/// The records a task can be on (§3.7).
enum TaskEntityType {
  CUSTOMER,
  INVOICE,
  PAYMENT;

  String get wire => name;

  String get noun => name.toLowerCase();

  /// Whom it can be assigned to follows the record, as an email's recipients do (§5.9.1).
  EmailEntityType get emailType => switch (this) {
        CUSTOMER => EmailEntityType.customer,
        INVOICE => EmailEntityType.invoice,
        PAYMENT => EmailEntityType.payment,
      };

  String route(int id) => '${emailType.routeBase}/$id';

  static TaskEntityType? fromWire(Object? wire) => TaskEntityType.values.asNameMap()[wire];
}

final DateFormat _wireDate = DateFormat('yyyy-MM-dd');

/// A due date as the server takes it: the calendar day picked, never shifted to UTC (G21).
String taskDateWire(DateTime day) => _wireDate.format(day);

DateTime? _day(Object? raw) {
  if (raw is! String) return null;
  final d = DateTime.tryParse(raw);
  return d == null ? null : DateTime(d.year, d.month, d.day);
}

DateTime? _instant(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;

/// One task (§6.12 `TaskDto`). Everything but the id, title and status is optional: a list row
/// and a past date's copy both carry less (in historical mode `version` is null and both flags
/// are false).
class TaskItem {
  final int id;
  final String title;
  final String? description;
  final TaskStatus status;
  final DateTime? dueDate;
  final bool overdue;
  final TaskEntityType? entityType;
  final int? entityId;
  final String? entityLabel;

  /// The record it was on has since been deleted; the task stays, cancelled if it was open.
  final bool entityDeleted;
  final int? customerId;
  final String? customerName;
  final int? regionId;
  final String? regionCode;
  final String? regionName;
  final List<RecordAssignee> assignees;
  final bool assignedToMe;

  /// USER or AUTOMATION (D4).
  final String? origin;
  final int? ruleId;
  final String? ruleName;
  final String? createdByName;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final DateTime? completedAt;
  final int? version;

  /// What this user may do to it in its region (D14): edit and cancel it, or move it along as one
  /// of its assignees. Null on an older server; the privileges answer then.
  final bool? canEdit;
  final bool? canChangeStatus;

  const TaskItem({
    required this.id,
    required this.title,
    required this.status,
    this.description,
    this.dueDate,
    this.overdue = false,
    this.entityType,
    this.entityId,
    this.entityLabel,
    this.entityDeleted = false,
    this.customerId,
    this.customerName,
    this.regionId,
    this.regionCode,
    this.regionName,
    this.assignees = const [],
    this.assignedToMe = false,
    this.origin,
    this.ruleId,
    this.ruleName,
    this.createdByName,
    this.createdAt,
    this.updatedAt,
    this.completedAt,
    this.version,
    this.canEdit,
    this.canChangeStatus,
  });

  factory TaskItem.fromJson(Map<String, dynamic> json) {
    final rule = json['rule'];
    final createdBy = json['createdBy'];
    return TaskItem(
      id: (json['id'] as num).toInt(),
      title: json['title'] as String? ?? '',
      description: json['description'] as String?,
      status: parseTaskStatus(json['status']),
      dueDate: _day(json['dueDate']),
      overdue: json['overdue'] as bool? ?? false,
      entityType: TaskEntityType.fromWire(json['entityType']),
      entityId: (json['entityId'] as num?)?.toInt(),
      entityLabel: json['entityLabel'] as String?,
      entityDeleted: json['entityDeleted'] as bool? ?? false,
      customerId: (json['customerId'] as num?)?.toInt(),
      customerName: json['customerName'] as String?,
      regionId: (json['regionId'] as num?)?.toInt(),
      regionCode: json['regionCode'] as String?,
      regionName: json['regionName'] as String?,
      assignees: RecordAssignee.listFrom(json['assignees']) ?? const [],
      assignedToMe: json['assignedToMe'] as bool? ?? false,
      origin: json['origin'] as String?,
      ruleId: rule is Map ? (rule['id'] as num?)?.toInt() : (json['ruleId'] as num?)?.toInt(),
      ruleName: rule is Map ? rule['name'] as String? : null,
      createdByName: createdBy is Map
          ? (createdBy['name'] as String? ?? createdBy['username'] as String?)
          : null,
      createdAt: _instant(json['createdAt']),
      updatedAt: _instant(json['updatedAt']),
      completedAt: _instant(json['completedAt']),
      version: (json['version'] as num?)?.toInt(),
      canEdit: json['canEdit'] as bool?,
      canChangeStatus: json['canChangeStatus'] as bool?,
    );
  }

  bool get isOpen => taskIsOpen(status);

  /// Where the record it is on opens, unless that record is gone.
  String? get recordRoute =>
      entityType == null || entityId == null || entityDeleted ? null : entityType!.route(entityId!);

  String get recordText =>
      entityLabel ??
      (entityType == null ? '—' : '${entityType!.emailType.label} #${entityId ?? '?'}');

  /// The assignees as one line: "Cara Collections, Collection POC (customer)".
  String get assigneesText => assignees.isEmpty
      ? 'Nobody'
      : assignees.map((a) => a.label.isEmpty ? '—' : a.label).join(', ');
}

/// A status a task can be moved to, and what the button that does it says (§5.9.2).
enum TaskMove {
  start(TaskStatus.IN_PROGRESS, 'Start', 'START'),
  done(TaskStatus.DONE, 'Mark done', 'MARK_DONE'),
  reopen(TaskStatus.OPEN, 'Reopen', 'REOPEN'),
  cancel(TaskStatus.CANCELLED, 'Cancel task', 'CANCEL');

  final TaskStatus to;
  final String label;

  /// The bulk action that makes the same move (§6.12).
  final String bulkAction;
  const TaskMove(this.to, this.label, this.bulkAction);
}

/// The moves [t] allows from where it stands: an assignee may start, finish, put a started task
/// back to open and reopen a finished one (§5.9.2); cancelling, and reopening a cancelled one, take
/// TASK_MANAGE ([canEdit]).
List<TaskMove> taskMovesFor(TaskItem t, {required bool canEdit, required bool canChangeStatus}) {
  return [
    if (t.status == TaskStatus.OPEN && canChangeStatus) TaskMove.start,
    if (t.isOpen && canChangeStatus) TaskMove.done,
    if ((t.status == TaskStatus.DONE || t.status == TaskStatus.IN_PROGRESS) && canChangeStatus)
      TaskMove.reopen,
    if (t.status == TaskStatus.CANCELLED && canEdit) TaskMove.reopen,
    if (t.isOpen && canEdit) TaskMove.cancel,
  ];
}
