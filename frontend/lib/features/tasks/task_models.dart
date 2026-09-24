import '../../shared/models/privileges.dart';

/// What a task can be about (A6).
///
/// The same three kinds `DocumentEntityType` offers, and deliberately not that enum: the two
/// vocabularies happen to agree today and belong to different features, and the backend's
/// `TaskEntityType` is its own enum for the same reason. The wire value, the route and the API
/// path all follow from the name, so the tab, the list and the server agree on one spelling (A6).
enum TaskEntityType {
  customer,
  invoice,
  payment;

  String get wire => name.toUpperCase();

  String get noun => name;

  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  String get routeBase => '/${name}s';

  String link(int id) => '$routeBase/$id';

  /// What somebody must hold to open the record a task is about. The list links the subject only
  /// when they hold it: a task carries its subject's LABEL — an invoice number — and offering a
  /// link to a page the router would bounce them off is worse than plain text (A6, UI-10).
  String get recordViewPrivilege => switch (this) {
        customer => Privileges.customerView,
        invoice => Privileges.invoiceView,
        payment => Privileges.paymentView,
      };

  static TaskEntityType? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// Where a piece of work has got to. Four constants, mirroring the server's TaskStatus (A6).
enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }

extension TaskStatusLabel on TaskStatus {
  String get label => switch (this) {
        TaskStatus.OPEN => 'Open',
        TaskStatus.IN_PROGRESS => 'In progress',
        TaskStatus.DONE => 'Done',
        TaskStatus.CANCELLED => 'Cancelled',
      };

  /// Nothing more happens to a task in this state, so no control that would act on it is
  /// offered — the same sentence `TaskStatus.terminal()` says on the server (A6).
  bool get terminal => this == TaskStatus.DONE || this == TaskStatus.CANCELLED;
}

/// An unknown constant reads as OPEN, the house convention every other status parser follows. It
/// is the safe direction for a READ — the row still appears — and every control is gated on the
/// server's answer to the write, never on this one (A6).
TaskStatus parseTaskStatus(String? s) =>
    TaskStatus.values.firstWhere((e) => e.name == s, orElse: () => TaskStatus.OPEN);

/// Somebody on a task, and WHY they are on it.
///
/// [source] is "USER" when a person picked them by name, or a stored RoleRef token such as
/// `ROLE:CUSTOMER:COLLECTION_POC` when a rule fanned a role out to whoever held it. Only the
/// automation engine writes the second kind, so until A-CONSUMER lands every seat reads "Picked
/// by name" — which is true, and stays true (A6, A3).
class TaskAssignee {
  final int? userId;
  final String name;
  final String? username;
  final String source;

  static const sourceUser = 'USER';

  const TaskAssignee({
    this.userId,
    required this.name,
    this.username,
    this.source = sourceUser,
  });

  factory TaskAssignee.fromJson(Map<String, dynamic> json) => TaskAssignee(
        userId: (json['userId'] as num?)?.toInt(),
        name: json['name'] as String? ?? '—',
        username: json['username'] as String?,
        source: json['source'] as String? ?? sourceUser,
      );

  String get display => username == null || username!.isEmpty ? name : '$name (@$username)';

  /// How the seat came about, in words, for the chip's tooltip. A RoleRef token is decoded the
  /// way `RoleRef.label(type)` decodes it on the server — "Collection POC (customer)" — because
  /// the reader is being told which seat this person holds, not which token was stored. Anything
  /// unreadable is shown verbatim rather than guessed at (A6, A3).
  String sourceLabel([TaskEntityType? type]) {
    if (source == sourceUser) return 'Picked by name';
    if (!source.startsWith('ROLE:')) return source;
    final parts = source.substring('ROLE:'.length).split(':');
    final role = _roleLabel(parts.last);
    if (role == null) return source;
    // A token written before levels existed carries the role alone and claims no level (L7).
    if (parts.length < 2) return role;
    return switch (parts.first) {
      'CUSTOMER' => '$role (customer)',
      'RECORD' => '$role (this ${type?.noun ?? 'record'})',
      _ => source,
    };
  }

  static String? _roleLabel(String role) => switch (role) {
        'SALES_POC' => 'Sales POC',
        'CUSTOMER_SUCCESS_POC' => 'Customer Success POC',
        'COLLECTION_POC' => 'Collection POC',
        _ => null,
      };
}

/// One task, over the exact TaskDto wire shape.
///
/// Unknown keys are ignored, the house rule that makes every later backend field wire-compatible
/// with the shipped client. There is deliberately no `approvalPending` here: PendingTargetType has
/// no TASK constant and the server's DTO leaves the slot free on purpose (A6, B2).
class Task {
  final int id;
  final TaskEntityType? entityType;
  final int? entityId;
  final String entityLabel;
  final int? customerId;
  final String? customerName;
  final String title;
  final String? notes;
  final DateTime? dueDate;
  final TaskStatus status;

  /// Worked out by the server from today's date, exactly as an invoice's overdue flag is: it is
  /// not a status, so it sits beside the status chip rather than replacing it (A6, D3).
  final bool overdue;
  final List<TaskAssignee> assignees;
  final int? createdByUserId;

  /// Set when an automation rule made this task rather than a person. Until A-UI declares
  /// /automation/rules/:id there is nowhere to link to, so the provenance reads as text (A6, A5).
  final int? createdByRuleId;
  final int? createdByStepId;
  final int? completedByUserId;
  final DateTime? completedAt;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  /// The branch this task's account is in. Null means the server did not say, and every per-record
  /// control falls back to the company-wide answer then (B1).
  final int? regionId;
  final String? regionName;

  const Task({
    required this.id,
    this.entityType,
    this.entityId,
    this.entityLabel = '',
    this.customerId,
    this.customerName,
    required this.title,
    this.notes,
    this.dueDate,
    this.status = TaskStatus.OPEN,
    this.overdue = false,
    this.assignees = const [],
    this.createdByUserId,
    this.createdByRuleId,
    this.createdByStepId,
    this.completedByUserId,
    this.completedAt,
    this.createdAt,
    this.updatedAt,
    this.regionId,
    this.regionName,
  });

  factory Task.fromJson(Map<String, dynamic> json) => Task(
        id: (json['id'] as num).toInt(),
        entityType: TaskEntityType.fromWire(json['entityType'] as String?),
        entityId: (json['entityId'] as num?)?.toInt(),
        entityLabel: json['entityLabel'] as String? ?? '',
        customerId: (json['customerId'] as num?)?.toInt(),
        customerName: json['customerName'] as String?,
        title: json['title'] as String? ?? '',
        notes: json['notes'] as String?,
        dueDate: json['dueDate'] == null ? null : DateTime.tryParse('${json['dueDate']}'),
        status: parseTaskStatus(json['status'] as String?),
        overdue: json['overdue'] as bool? ?? false,
        assignees: ((json['assignees'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(TaskAssignee.fromJson)
            .toList(),
        createdByUserId: (json['createdByUserId'] as num?)?.toInt(),
        createdByRuleId: (json['createdByRuleId'] as num?)?.toInt(),
        createdByStepId: (json['createdByStepId'] as num?)?.toInt(),
        completedByUserId: (json['completedByUserId'] as num?)?.toInt(),
        completedAt:
            json['completedAt'] == null ? null : DateTime.tryParse('${json['completedAt']}'),
        createdAt: json['createdAt'] == null ? null : DateTime.tryParse('${json['createdAt']}'),
        updatedAt: json['updatedAt'] == null ? null : DateTime.tryParse('${json['updatedAt']}'),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
      );

  /// Where the record this task is about lives, or null when the server named neither.
  String? get recordLink =>
      entityType == null || entityId == null ? null : entityType!.link(entityId!);

  String get recordLabel => entityLabel.isEmpty
      ? '${entityType?.label ?? 'Record'} #${entityId ?? '—'}'
      : entityLabel;

  /// Made by a rule rather than by a person (A5).
  bool get automated => createdByRuleId != null;
}

/// The five figures GET /api/tasks/summary answers with, counted inside ONE aggregate over the
/// SAME filters the list ran — so a tile and the rows behind it cannot disagree. There is no
/// awaiting-approval figure, for the same reason [Task] has no approvalPending (A6, B2).
class TaskSummary {
  final int open;
  final int inProgress;
  final int overdue;
  final int dueThisWeek;
  final int done;

  const TaskSummary({
    this.open = 0,
    this.inProgress = 0,
    this.overdue = 0,
    this.dueThisWeek = 0,
    this.done = 0,
  });

  factory TaskSummary.fromJson(Map<String, dynamic> json) => TaskSummary(
        open: (json['open'] as num?)?.toInt() ?? 0,
        inProgress: (json['inProgress'] as num?)?.toInt() ?? 0,
        overdue: (json['overdue'] as num?)?.toInt() ?? 0,
        dueThisWeek: (json['dueThisWeek'] as num?)?.toInt() ?? 0,
        done: (json['done'] as num?)?.toInt() ?? 0,
      );
}
