import '../../features/email/email_entity.dart';
import 'assignee.dart';

/// The records a piece of work can be raised on (T1), mirroring the backend's `TaskEntityType`.
/// The wire value, the route and the label all follow from the name, so a list page, a details
/// page and the backend agree on one spelling — the same shape `DocumentEntityType` has for
/// documents. It is deliberately short: work is raised against the three things a collections
/// person actually works on.
enum TaskEntityType {
  customer,
  invoice,
  payment;

  /// 'INVOICE', as the API spells it.
  String get wire => name.toUpperCase();

  /// 'invoice', for sentences: "No tasks on this invoice yet."
  String get noun => name;

  /// 'Invoice', for labels.
  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  /// '/invoices' — the list page; a record's page is `$routeBase/$id`.
  String get routeBase => '/${name}s';

  /// The same record as the email feature names it. Everything about a record that an assignee
  /// needs — which roles it offers and who holds each one — is already worked out per kind by the
  /// email compose context, so tasks borrow that rather than growing a second table beside it
  /// that would drift (A4), exactly as the backend's `TaskEntityType.toEmailEntityType()` does.
  EmailEntityType get emailType => switch (this) {
        customer => EmailEntityType.customer,
        invoice => EmailEntityType.invoice,
        payment => EmailEntityType.payment,
      };

  /// The kind a wire value names, or null when it names none — for a query parameter or a rule's
  /// stored config, where there may be no kind at all.
  static TaskEntityType? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// The kind a task is on. A task always has one, and this enum is the backend's own value for
/// value, so there is nothing to fall back to in practice; a value from some later server reads
/// as a customer task rather than making the row — or the whole list — unreadable. The same
/// bargain `parseDisputeTarget` makes.
TaskEntityType parseTaskEntityType(String? wire) =>
    TaskEntityType.fromWire(wire) ?? TaskEntityType.customer;

/// Where a piece of work has got to (T2). Unlike a promise's status, which is recomputed from
/// payment facts, a task's is only ever what a person said it is: nothing in the system can know
/// that somebody has chased a customer.
enum TaskStatus { OPEN, IN_PROGRESS, DONE, CANCELLED }

TaskStatus parseTaskStatus(String? s) =>
    TaskStatus.values.firstWhere((e) => e.name == s, orElse: () => TaskStatus.OPEN);

String taskStatusLabel(TaskStatus s) => switch (s) {
      TaskStatus.OPEN => 'Open',
      TaskStatus.IN_PROGRESS => 'In progress',
      TaskStatus.DONE => 'Done',
      TaskStatus.CANCELLED => 'Cancelled',
    };

/// True once the task is off the list, however it got there. DONE and CANCELLED are told apart
/// only for the reader — the work happened, or it never needed to — and this is what the open
/// count, the overdue flag and the greyed-out row all key on, so a new closing status is one
/// value here and nothing else.
bool taskStatusIsTerminal(TaskStatus s) =>
    s == TaskStatus.DONE || s == TaskStatus.CANCELLED;

/// One task as the caller may see it (the backend's `TaskDto`).
class Task {
  final int id;

  final TaskEntityType entityType;
  final int entityId;

  /// What the record is called and where it opens, so a "my tasks" row needs nothing else to
  /// render: the task list never loads the records behind its rows.
  final String entityLabel;
  final String? entityLink;

  /// Whose customer the work is on, for the book filter; null when the record has no customer.
  final int? customerId;
  final String title;
  final DateTime? dueDate;
  final TaskStatus status;

  /// The server's own wording for [status] — "In progress", not "IN_PROGRESS". Kept rather than
  /// re-derived so a status the app has not heard of still reads as whatever the server calls it.
  final String statusLabel;

  /// Past its due date and still open. Worked out on the way out rather than stored, because it
  /// changes at midnight with nobody touching the row (T9), so it is never cached across a day.
  final bool overdue;
  final String? notes;

  /// Who it is for, resolved against the record as it stands now (A2). Empty means nobody.
  final List<Assignee> assignees;

  /// Null for a viewer who may not see staff identity.
  final int? createdByUserId;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  /// When it reached a terminal status; null while it is still live.
  final DateTime? completedAt;

  const Task({
    required this.id,
    required this.entityType,
    required this.entityId,
    required this.entityLabel,
    this.entityLink,
    this.customerId,
    required this.title,
    this.dueDate,
    required this.status,
    required this.statusLabel,
    this.overdue = false,
    this.notes,
    this.assignees = const [],
    this.createdByUserId,
    this.createdAt,
    this.updatedAt,
    this.completedAt,
  });

  factory Task.fromJson(Map<String, dynamic> json) {
    final status = parseTaskStatus(json['status'] as String?);
    return Task(
      id: (json['id'] as num).toInt(),
      entityType: parseTaskEntityType(json['entityType'] as String?),
      entityId: (json['entityId'] as num?)?.toInt() ?? 0,
      entityLabel: json['entityLabel'] as String? ?? '',
      entityLink: json['entityLink'] as String?,
      customerId: (json['customerId'] as num?)?.toInt(),
      title: json['title'] as String? ?? '',
      dueDate: _date(json['dueDate']),
      status: status,
      statusLabel: json['statusLabel'] as String? ?? taskStatusLabel(status),
      overdue: json['overdue'] as bool? ?? false,
      notes: json['notes'] as String?,
      assignees: ((json['assignees'] as List?) ?? const [])
          .cast<Map>()
          .map((m) => Assignee.fromJson(m.cast<String, dynamic>()))
          .toList(),
      createdByUserId: (json['createdByUserId'] as num?)?.toInt(),
      createdAt: _date(json['createdAt']),
      updatedAt: _date(json['updatedAt']),
      completedAt: _date(json['completedAt']),
    );
  }

  /// Off the list, however it got there.
  bool get isTerminal => taskStatusIsTerminal(status);

  /// The tokens that would assign this task again: what the edit form starts from.
  List<EmailToken> get assigneeTokens => [
        for (final a in assignees)
          if (a.token != null) a.token!,
      ];

  /// "Anil, Collection POC · Bala", or "Unassigned" when it is nobody's. A task with no assignee
  /// is not an error — it is work nobody has picked up — so it says so rather than reading blank.
  String get assigneeText =>
      assignees.isEmpty ? 'Unassigned' : assignees.map((a) => a.display).join(', ');
}

DateTime? _date(Object? raw) => raw == null ? null : DateTime.tryParse(raw.toString());
