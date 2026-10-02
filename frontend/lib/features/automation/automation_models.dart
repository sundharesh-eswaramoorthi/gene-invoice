import 'dart:math';

import 'package:flutter/foundation.dart';

import '../../core/table/table_models.dart';
import '../../shared/models/privileges.dart';
import '../email/email_entity.dart';

/// Where a rule stands (§6.14). The wire names are the server's; an unknown one reads as ACTIVE,
/// like the other status parsers (G25).
enum RuleStatus { ACTIVE, DISABLED, SUSPENDED }

RuleStatus parseRuleStatus(Object? raw) => RuleStatus.values.asNameMap()[raw] ?? RuleStatus.ACTIVE;

String ruleStatusLabel(RuleStatus s) => switch (s) {
      RuleStatus.ACTIVE => 'Active',
      RuleStatus.DISABLED => 'Disabled',
      RuleStatus.SUSPENDED => 'Suspended',
    };

/// Why the engine stopped a rule by itself (§3.6 `SuspendReason`), in words.
String suspendReasonLabel(String? reason) => switch (reason) {
      'OWNER_INACTIVE' => 'Its owner is no longer active',
      'OWNER_NOT_STAFF' => 'Its owner is not a staff user',
      'OWNER_LACKS_RIGHTS' => 'Its owner no longer holds the access it needs',
      'INVALID_DEFINITION' => 'It can no longer be run as written',
      'RATE_LIMITED' => 'It failed too often',
      null => 'Suspended',
      _ => reason,
    };

/// What starts a rule (A1): a record being created or updated, a daily or weekly slot, or only a
/// person pressing "Run now".
enum TriggerKind {
  ENTITY_CREATED,
  ENTITY_UPDATED,
  ENTITY_CREATED_OR_UPDATED,
  SCHEDULE_DAILY,
  SCHEDULE_WEEKLY,
  MANUAL;

  bool get isSchedule => this == SCHEDULE_DAILY || this == SCHEDULE_WEEKLY;

  String get label => switch (this) {
        ENTITY_CREATED => 'When one is created',
        ENTITY_UPDATED => 'When one is updated',
        ENTITY_CREATED_OR_UPDATED => 'When one is created or updated',
        SCHEDULE_DAILY => 'Every day',
        SCHEDULE_WEEKLY => 'Every week',
        MANUAL => 'Only when run by hand',
      };

  static TriggerKind parse(Object? raw) =>
      TriggerKind.values.asNameMap()[raw] ?? TriggerKind.ENTITY_CREATED;
}

/// The records a rule is about (A1).
enum TriggerEntity {
  CUSTOMER,
  INVOICE,
  PAYMENT;

  String get label => switch (this) {
        CUSTOMER => 'Customer',
        INVOICE => 'Invoice',
        PAYMENT => 'Payment',
      };

  String get plural => switch (this) {
        CUSTOMER => 'customers',
        INVOICE => 'invoices',
        PAYMENT => 'payments',
      };

  /// The list page whose filters a condition is made of (A2): its table schema's entity.
  String get schemaEntity => plural;

  /// Records are picked the way the email compose picks them.
  EmailEntityType get emailType => switch (this) {
        CUSTOMER => EmailEntityType.customer,
        INVOICE => EmailEntityType.invoice,
        PAYMENT => EmailEntityType.payment,
      };

  static TriggerEntity parse(Object? raw) =>
      TriggerEntity.values.asNameMap()[raw] ?? TriggerEntity.INVOICE;
}

const weekdays = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

String weekdayLabel(String? day) =>
    day == null || day.isEmpty ? '—' : '${day[0]}${day.substring(1).toLowerCase()}';

/// The device's own UTC offset as a zone the server parses (`+05:30`), or `UTC` at zero — what a
/// new schedule starts with (§7.7.3).
String deviceZone([DateTime? at]) {
  final offset = (at ?? DateTime.now()).timeZoneOffset;
  if (offset == Duration.zero) return 'UTC';
  final minutes = offset.inMinutes.abs();
  final sign = offset.isNegative ? '-' : '+';
  String two(int n) => n.toString().padLeft(2, '0');
  return '$sign${two(minutes ~/ 60)}:${two(minutes % 60)}';
}

/// "When an invoice is created", "Every Monday at 09:00 (+05:30)", "Only when run by hand".
String describeTrigger(TriggerKind kind, TriggerEntity entity,
    {String? time, String? day, String? zone}) {
  final noun = entity.label.toLowerCase();
  final article = entity == TriggerEntity.INVOICE ? 'an' : 'a';
  final at =
      [if (time != null) 'at $time', if (zone != null && zone.isNotEmpty) '($zone)'].join(' ');
  return switch (kind) {
    TriggerKind.ENTITY_CREATED => 'When $article $noun is created',
    TriggerKind.ENTITY_UPDATED => 'When $article $noun is updated',
    TriggerKind.ENTITY_CREATED_OR_UPDATED => 'When $article $noun is created or updated',
    TriggerKind.SCHEDULE_DAILY => 'Every day${at.isEmpty ? '' : ' $at'}',
    TriggerKind.SCHEDULE_WEEKLY => 'Every ${weekdayLabel(day)}${at.isEmpty ? '' : ' $at'}',
    TriggerKind.MANUAL => 'Only when run by hand',
  };
}

/// A rule's trigger (§6.14 `trigger`). Every key is always sent, null where it does not apply, so
/// the request has one shape whatever the kind.
@immutable
class RuleTrigger {
  final TriggerKind kind;
  final TriggerEntity entity;

  /// `HH:mm`, for schedules.
  final String? time;

  /// A `DayOfWeek` name, for weekly schedules.
  final String? day;
  final String? zone;

  /// `ONE` or `ALL`, for schedules: after missed slots, one catch-up run or one per slot.
  final String? catchUp;

  const RuleTrigger({
    required this.kind,
    required this.entity,
    this.time,
    this.day,
    this.zone,
    this.catchUp,
  });

  factory RuleTrigger.fromJson(Map<String, dynamic> json) => RuleTrigger(
        kind: TriggerKind.parse(json['kind']),
        entity: TriggerEntity.parse(json['entityType']),
        time: json['time'] as String?,
        day: json['day'] as String?,
        zone: json['zone'] as String?,
        catchUp: json['catchUp'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'kind': kind.name,
        'entityType': entity.name,
        'time': kind.isSchedule ? time : null,
        'day': kind == TriggerKind.SCHEDULE_WEEKLY ? day : null,
        'zone': zone,
        'catchUp': kind.isSchedule ? (catchUp ?? 'ONE') : null,
      };

  String get description => describeTrigger(kind, entity, time: time, day: day, zone: zone);
}

/// The server's limits on a condition tree (§5.10.2).
abstract final class ConditionLimits {
  static const depth = 4;
  static const leaves = 30;
  static const childrenPerGroup = 20;
}

/// One node of a rule's condition (A2): a list-page filter, or an AND/OR group of nodes.
@immutable
sealed class ConditionNode {
  const ConditionNode();

  Map<String, dynamic> toJson();

  int get leafCount;

  /// A leaf is `{"filter":"…"}`; a group is `{"op":"AND","children":[…]}`. Anything else — an
  /// unparseable filter, say — is dropped rather than shown as something it is not.
  static ConditionNode? fromJson(Object? json) {
    if (json is! Map) return null;
    final filter = json['filter'];
    if (filter is String) {
      final f = TableFilter.parse(filter);
      return f == null ? null : ConditionLeaf(f);
    }
    return ConditionGroup.fromJson(json.cast<String, dynamic>());
  }
}

@immutable
class ConditionLeaf extends ConditionNode {
  final TableFilter filter;
  const ConditionLeaf(this.filter);

  @override
  Map<String, dynamic> toJson() => {'filter': filter.wire};

  @override
  int get leafCount => 1;

  @override
  bool operator ==(Object other) => other is ConditionLeaf && other.filter == filter;

  @override
  int get hashCode => filter.hashCode;
}

@immutable
class ConditionGroup extends ConditionNode {
  /// `AND` (all of these) or `OR` (any of these).
  final String op;
  final List<ConditionNode> children;

  const ConditionGroup(this.op, this.children);

  /// A rule with no condition: every record.
  static const empty = ConditionGroup('AND', []);

  factory ConditionGroup.fromJson(Map<String, dynamic> json) => ConditionGroup(
        json['op'] == 'OR' ? 'OR' : 'AND',
        [
          for (final c in (json['children'] as List?) ?? const [])
            if (ConditionNode.fromJson(c) case final ConditionNode node) node,
        ],
      );

  bool get isEmpty => children.isEmpty;

  @override
  Map<String, dynamic> toJson() => {
        'op': op,
        'children': [for (final c in children) c.toJson()],
      };

  /// What the request carries: null for no condition at all, which the server reads as every
  /// record (§5.10.2).
  Map<String, dynamic>? toRequest() => isEmpty ? null : toJson();

  @override
  int get leafCount => children.fold(0, (sum, c) => sum + c.leafCount);

  ConditionGroup withOp(String newOp) => ConditionGroup(newOp, children);

  ConditionGroup withChildren(List<ConditionNode> next) =>
      ConditionGroup(op, List.unmodifiable(next));

  /// Only this group's own leaves, and only when it has no nested groups — the tree a list page
  /// can show (`listLink`, §6.14).
  List<TableFilter>? get flatAndFilters => op == 'AND' && children.every((c) => c is ConditionLeaf)
      ? [for (final c in children) (c as ConditionLeaf).filter]
      : null;

  @override
  bool operator ==(Object other) =>
      other is ConditionGroup && other.op == op && listEquals(other.children, children);

  @override
  int get hashCode => Object.hash(op, Object.hashAll(children));
}

final Random _random = Random.secure();

/// A fresh action key: the client makes one (a v4 UUID) when an action is added, and keeps it for
/// the action's life, because "once per record" is remembered against it (§5.10.5).
String newActionKey() {
  final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}

/// What an action does each time: once per record (the default) or on every event.
String repeatLabel(String? repeat) => repeat == 'EVERY_TIME' ? 'Every time' : 'Once per record';

/// One of a rule's actions (A3). The whole JSON is kept, so a round trip through the editor sends
/// back exactly what the server gave — keys included — whatever this build knows of the type.
@immutable
sealed class RuleAction {
  final Map<String, dynamic> json;
  const RuleAction(this.json);

  factory RuleAction.fromJson(Map<String, dynamic> json) => switch (json['type']) {
        'CREATE_TASK' => CreateTaskAction(json),
        'CREATE_PROMISE' => CreatePromiseAction(json),
        'CREATE_DISPUTE' => CreateDisputeAction(json),
        'SEND_EMAIL' => SendEmailAction(json),
        _ => UnknownAction(json),
      };

  String get key => json['key'] as String? ?? '';
  String get type => json['type'] as String? ?? '';
  String get repeat => json['repeat'] as String? ?? 'ONCE_PER_RECORD';

  /// "Create a task", "Send an email", … .
  String get typeLabel;

  /// The action's own words, when it has any: a task's title, an email's subject.
  String? get detail;

  String get summary => detail == null || detail!.isEmpty ? typeLabel : '$typeLabel: $detail';

  /// This action with a key of its own: the one it has, or a new one if the server sent none.
  /// Done once, when a rule is loaded, so every later save sends the same key.
  RuleAction withKey() =>
      key.isEmpty ? RuleAction.fromJson({...json, 'key': newActionKey()}) : this;

  Map<String, dynamic> toJson() => json;
}

class CreateTaskAction extends RuleAction {
  const CreateTaskAction(super.json);
  @override
  String get typeLabel => 'Create a task';
  @override
  String? get detail => json['title'] as String?;
}

class CreatePromiseAction extends RuleAction {
  const CreatePromiseAction(super.json);
  @override
  String get typeLabel => 'Create a promise';
  @override
  String? get detail => json['notes'] as String?;
}

class CreateDisputeAction extends RuleAction {
  const CreateDisputeAction(super.json);
  @override
  String get typeLabel => 'Open a dispute';
  @override
  String? get detail => json['reason'] as String?;
}

class SendEmailAction extends RuleAction {
  const SendEmailAction(super.json);
  @override
  String get typeLabel => 'Send an email';
  @override
  String? get detail => json['subject'] as String?;
}

class UnknownAction extends RuleAction {
  const UnknownAction(super.json);
  @override
  String get typeLabel => type.isEmpty ? 'Action' : type;
  @override
  String? get detail => null;
}

/// The kinds of action a rule can take (A3), in the order they are offered.
enum ActionType {
  CREATE_TASK,
  CREATE_PROMISE,
  CREATE_DISPUTE,
  SEND_EMAIL;

  String get label => switch (this) {
        CREATE_TASK => 'Create a task',
        CREATE_PROMISE => 'Create a promise',
        CREATE_DISPUTE => 'Open a dispute',
        SEND_EMAIL => 'Send an email',
      };

  /// What the owner must hold in the record's region for the action to act there (§5.10.5).
  String get privilege => switch (this) {
        CREATE_TASK => Privileges.taskManage,
        CREATE_PROMISE => Privileges.promiseManage,
        CREATE_DISPUTE => Privileges.disputeManage,
        SEND_EMAIL => Privileges.emailSend,
      };

  static ActionType? parse(Object? raw) => ActionType.values.asNameMap()[raw];
}

/// A rule has one to five actions (§6.14).
const maxRuleActions = 5;

/// The amounts a promise can take from the record (§5.10.5), each offered where it exists.
const promiseAmountFields = <String, String>{
  'Customer.Outstanding': "Customer's outstanding balance",
  'Customer.OverdueAmount': "Customer's overdue amount",
  'Invoice.Balance': 'Invoice balance',
  'Invoice.Total': 'Invoice total',
  'Payment.Amount': 'Payment amount',
};

/// Whether [field] (`Invoice.Balance`, …) is about records a rule on [entity] has: the customer's
/// always, the record's own only on its own kind of rule.
bool fieldApplies(String field, TriggerEntity entity) {
  final level = field.split('.').first;
  return level == 'Customer' || level == entity.label;
}

/// A fresh action of [type] for a rule on [entity], with a key of its own and the defaults the
/// server would take: once per record, and not twice while an earlier one is still open.
RuleAction newRuleAction(ActionType type, TriggerEntity entity) =>
    RuleAction.fromJson(switch (type) {
      ActionType.CREATE_TASK => {
          'key': newActionKey(),
          'type': type.name,
          'repeat': 'ONCE_PER_RECORD',
          'title': '',
          'description': null,
          'due': {'base': 'TODAY', 'offsetDays': 2},
          'assignees': <Object>[],
          'onlyIfNoOpenTask': true,
        },
      ActionType.CREATE_PROMISE => {
          'key': newActionKey(),
          'type': type.name,
          'repeat': 'ONCE_PER_RECORD',
          'amount': {
            'source': 'FIELD',
            'field': switch (entity) {
              TriggerEntity.INVOICE => 'Invoice.Balance',
              TriggerEntity.PAYMENT => 'Payment.Amount',
              TriggerEntity.CUSTOMER => 'Customer.OverdueAmount',
            },
          },
          'promisedDate': {'base': 'TODAY', 'offsetDays': 7},
          'collectionPoc': null,
          'assignees': <Object>[],
          'notes': null,
          'linkRecord': entity == TriggerEntity.INVOICE,
          'onlyIfNoOpenPromise': true,
        },
      ActionType.CREATE_DISPUTE => {
          'key': newActionKey(),
          'type': type.name,
          'repeat': 'ONCE_PER_RECORD',
          'reason': '',
          'proposedChange': 'none',
          'assignees': <Object>[],
        },
      ActionType.SEND_EMAIL => {
          'key': newActionKey(),
          'type': type.name,
          'repeat': 'ONCE_PER_RECORD',
          'from': null,
          'fallbackToOwner': true,
          'to': <Object>[],
          'subject': '',
          'body': '',
        },
    });

/// Any `{{Invoice.…}}` or `{{Payment.…}}` placeholder, with its fallback if it has one (§5.10.4).
final RegExp _recordPlaceholder =
    RegExp(r'\{\{\s*(Invoice|Payment)\.[A-Za-z]+\s*(?:\|[^{}|]{0,100})?\s*\}\}');

/// [text] without the placeholders a rule on [entity] cannot fill: another kind of record's own.
String? dropForeignPlaceholders(String? text, TriggerEntity entity) => text?.replaceAllMapped(
    _recordPlaceholder, (m) => m.group(1) == entity.label ? m.group(0)! : '');

/// The record-level roles differ from one kind of record to the next (a Sales POC is an
/// invoice's, a Collection POC a payment's), so a token at that level cannot move with the rule.
bool _foreignToken(Object? token) => token is Map && token['level'] == 'RECORD';

/// [action] made to fit a rule on [entity]: the placeholders, record-level people, amounts,
/// dates and proposals that only another kind of record has are taken out or put back to their
/// plain choice. The same action when nothing had to change.
RuleAction actionFor(RuleAction action, TriggerEntity entity) {
  final json = {...action.json};
  for (final field in const ['title', 'description', 'notes', 'reason', 'subject', 'body']) {
    final text = json[field];
    if (text is String) json[field] = dropForeignPlaceholders(text, entity);
  }
  for (final field in const ['assignees', 'to']) {
    final tokens = json[field];
    if (tokens is List) json[field] = [for (final t in tokens) if (!_foreignToken(t)) t];
  }
  for (final field in const ['collectionPoc', 'from']) {
    if (_foreignToken(json[field])) json[field] = null;
  }
  for (final field in const ['due', 'promisedDate']) {
    final date = json[field];
    if (date is Map && date['base'] == 'INVOICE_DUE_DATE' && entity != TriggerEntity.INVOICE) {
      json[field] = {...date, 'base': 'TODAY'};
    }
  }
  final amount = json['amount'];
  if (amount is Map && amount['source'] == 'FIELD' && amount['field'] is String) {
    if (!fieldApplies(amount['field'] as String, entity)) {
      json['amount'] = {...amount, 'field': 'Customer.Outstanding'};
    }
  }
  final proposal = json['proposedChange'];
  if ((proposal == 'cancel' && entity != TriggerEntity.INVOICE) ||
      (proposal == 'void' && entity != TriggerEntity.PAYMENT)) {
    json['proposedChange'] = 'none';
  }
  if (json['linkRecord'] == true && entity != TriggerEntity.INVOICE) json['linkRecord'] = false;
  return _sameJson(json, action.json) ? action : RuleAction.fromJson(json);
}

bool _sameJson(Object? a, Object? b) {
  if (a is Map && b is Map) {
    return a.length == b.length &&
        a.keys.every((k) => b.containsKey(k) && _sameJson(a[k], b[k]));
  }
  if (a is List && b is List) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!_sameJson(a[i], b[i])) return false;
    }
    return true;
  }
  return a == b;
}

/// Who a rule runs as (P3).
@immutable
class RuleOwner {
  final int? id;
  final String name;
  final bool active;
  const RuleOwner({required this.id, required this.name, this.active = true});

  static RuleOwner? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = (raw['id'] as num?)?.toInt();
    return RuleOwner(
      id: id,
      name: raw['name'] as String? ?? (id == null ? 'Someone' : 'User #$id'),
      active: raw['active'] as bool? ?? true,
    );
  }
}

DateTime? _instant(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;

/// `{"<userId>": "<name>"}`, keyed by id; what is not an id with a name is left out.
Map<int, String> _people(Object? raw) => raw is Map
    ? {
        for (final e in raw.entries)
          if (int.tryParse('${e.key}') case final id? when e.value is String) id: e.value as String,
      }
    : const {};

List<int> _ids(Object? raw) =>
    ((raw as List?) ?? const []).whereType<num>().map((e) => e.toInt()).toList();

List<String> _strings(Object? raw) =>
    ((raw as List?) ?? const []).map((e) => e.toString()).toList();

/// One row of the rules list (§6.14 `RuleSummary`).
class RuleSummary {
  final int id;
  final String name;
  final bool enabled;
  final RuleStatus status;
  final String? suspendedReason;
  final TriggerKind triggerKind;
  final TriggerEntity entity;
  final RuleOwner? owner;
  final List<int> regionIds;
  final String? lastRunStatus;
  final DateTime? lastRunAt;
  final DateTime? nextRunAt;

  const RuleSummary({
    required this.id,
    required this.name,
    required this.enabled,
    required this.status,
    required this.triggerKind,
    required this.entity,
    this.suspendedReason,
    this.owner,
    this.regionIds = const [],
    this.lastRunStatus,
    this.lastRunAt,
    this.nextRunAt,
  });

  factory RuleSummary.fromJson(Map<String, dynamic> json) => RuleSummary(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String? ?? 'Rule #${json['id']}',
        enabled: json['enabled'] as bool? ?? false,
        status: parseRuleStatus(json['status']),
        suspendedReason: json['suspendedReason'] as String?,
        triggerKind: TriggerKind.parse(json['triggerKind']),
        entity: TriggerEntity.parse(json['entityType']),
        owner: RuleOwner.fromJson(json['owner']),
        regionIds: _ids(json['regionIds']),
        lastRunStatus: json['lastRunStatus'] as String?,
        lastRunAt: _instant(json['lastRunAt']),
        nextRunAt: _instant(json['nextRunAt']),
      );
}

/// A rule's latest run, as its page shows it.
class RunBrief {
  final int id;
  final String? status;
  final DateTime? finishedAt;
  final int matched;
  final int done;
  final int skipped;
  final int failed;

  const RunBrief({
    required this.id,
    this.status,
    this.finishedAt,
    this.matched = 0,
    this.done = 0,
    this.skipped = 0,
    this.failed = 0,
  });

  static RunBrief? fromJson(Object? raw) {
    if (raw is! Map || raw['id'] is! num) return null;
    int n(String k) => (raw[k] as num?)?.toInt() ?? 0;
    return RunBrief(
      id: (raw['id'] as num).toInt(),
      status: raw['status'] as String?,
      finishedAt: _instant(raw['finishedAt']),
      matched: n('matched'),
      done: n('done'),
      skipped: n('skipped'),
      failed: n('failed'),
    );
  }
}

/// A whole rule (§6.14 `RuleDto`). On a past date it is the revision in force then, and every
/// flag is false (D14).
class Rule {
  final int id;
  final String name;
  final String? description;
  final bool enabled;
  final int? version;
  final RuleStatus status;
  final String? suspendedReason;
  final String? suspendedDetail;
  final RuleOwner? owner;
  final RuleTrigger trigger;
  final List<int> regionIds;
  final ConditionGroup condition;
  final List<RuleAction> actions;
  final int maxRecordsPerRun;
  final DateTime? nextRunAt;
  final RunBrief? lastRun;
  final List<String> warnings;
  final String? listLink;

  /// The names of the people its actions name by id (`{"12": "Priya Rao"}`), which the tokens
  /// themselves do not carry; empty when the server sends none.
  final Map<int, String> people;

  /// The owner may edit it; a non-owner AUTOMATION_ADMIN may too, but saving makes them the owner
  /// ([editTakesOwnership]) — as do Enable, Resume and Run now (§5.10.7).
  final bool? canEdit;
  final bool? editTakesOwnership;
  final bool? canRun;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  const Rule({
    required this.id,
    required this.name,
    required this.enabled,
    required this.status,
    required this.trigger,
    this.description,
    this.version,
    this.suspendedReason,
    this.suspendedDetail,
    this.owner,
    this.regionIds = const [],
    this.condition = ConditionGroup.empty,
    this.actions = const [],
    this.maxRecordsPerRun = 1000,
    this.nextRunAt,
    this.lastRun,
    this.warnings = const [],
    this.listLink,
    this.people = const {},
    this.canEdit,
    this.editTakesOwnership,
    this.canRun,
    this.createdAt,
    this.updatedAt,
  });

  factory Rule.fromJson(Map<String, dynamic> json) {
    final trigger = json['trigger'];
    final condition = json['condition'];
    return Rule(
      id: (json['id'] as num).toInt(),
      name: json['name'] as String? ?? 'Rule #${json['id']}',
      description: json['description'] as String?,
      enabled: json['enabled'] as bool? ?? false,
      version: (json['version'] as num?)?.toInt(),
      status: parseRuleStatus(json['status']),
      suspendedReason: json['suspendedReason'] as String?,
      suspendedDetail: json['suspendedDetail'] as String?,
      owner: RuleOwner.fromJson(json['owner']),
      trigger: trigger is Map
          ? RuleTrigger.fromJson(trigger.cast<String, dynamic>())
          : const RuleTrigger(kind: TriggerKind.ENTITY_CREATED, entity: TriggerEntity.INVOICE),
      regionIds: _ids(json['regionIds']),
      condition: condition is Map
          ? ConditionGroup.fromJson(condition.cast<String, dynamic>())
          : ConditionGroup.empty,
      actions: [
        for (final a in (json['actions'] as List?) ?? const [])
          if (a is Map) RuleAction.fromJson(a.cast<String, dynamic>()),
      ],
      maxRecordsPerRun: (json['maxRecordsPerRun'] as num?)?.toInt() ?? 1000,
      nextRunAt: _instant(json['nextRunAt']),
      lastRun: RunBrief.fromJson(json['lastRun']),
      warnings: _strings(json['warnings']),
      listLink: json['listLink'] as String?,
      people: _people(json['people']),
      canEdit: json['canEdit'] as bool?,
      editTakesOwnership: json['editTakesOwnership'] as bool?,
      canRun: json['canRun'] as bool?,
      createdAt: _instant(json['createdAt']),
      updatedAt: _instant(json['updatedAt']),
    );
  }
}

/// A record a run or an effect is about. Someone who may not see it gets only its type (§5.10.10).
class RunTarget {
  final String? type;
  final int? id;
  final String? label;
  final String? link;
  final bool hidden;

  const RunTarget({this.type, this.id, this.label, this.link, this.hidden = false});

  static RunTarget? fromJson(Object? raw) {
    if (raw is! Map) return null;
    return RunTarget(
      type: raw['type'] as String?,
      id: (raw['id'] as num?)?.toInt(),
      label: raw['label'] as String?,
      link: raw['link'] as String?,
      hidden: raw['hidden'] as bool? ?? false,
    );
  }
}

/// How a run stands (§3.6 `RunStatus`).
enum RunStatus { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED, SUPERSEDED }

RunStatus parseRunStatus(Object? raw) => RunStatus.values.asNameMap()[raw] ?? RunStatus.QUEUED;

String runStatusLabel(RunStatus s) => switch (s) {
      RunStatus.QUEUED => 'Queued',
      RunStatus.RUNNING => 'Running',
      RunStatus.SUCCEEDED => 'Succeeded',
      RunStatus.PARTIAL => 'Partly done',
      RunStatus.FAILED => 'Failed',
      RunStatus.SKIPPED => 'Skipped',
      RunStatus.SUPERSEDED => 'Superseded',
    };

/// One run of a rule (§6.14 `RunDto`). [partial] means the counters count only the records this
/// viewer can see; [statusAt] is how it stood on the date viewed.
class RunSummary {
  final int id;
  final int? ruleId;
  final int? ruleVersion;
  final int? eventId;
  final String? triggerType;
  final RunStatus status;
  final RunStatus? statusAt;
  final DateTime? scheduledFor;
  final int? lateBySeconds;
  final RunTarget? entity;
  final RuleOwner? requestedBy;
  final int matched;
  final int done;
  final int skipped;
  final int failed;
  final bool partial;
  final bool truncated;
  final String? message;
  final DateTime? createdAt;
  final DateTime? startedAt;
  final DateTime? finishedAt;

  const RunSummary({
    required this.id,
    required this.status,
    this.ruleId,
    this.ruleVersion,
    this.eventId,
    this.triggerType,
    this.statusAt,
    this.scheduledFor,
    this.lateBySeconds,
    this.entity,
    this.requestedBy,
    this.matched = 0,
    this.done = 0,
    this.skipped = 0,
    this.failed = 0,
    this.partial = false,
    this.truncated = false,
    this.message,
    this.createdAt,
    this.startedAt,
    this.finishedAt,
  });

  factory RunSummary.fromJson(Map<String, dynamic> json) {
    int n(String k) => (json[k] as num?)?.toInt() ?? 0;
    return RunSummary(
      id: (json['id'] as num).toInt(),
      ruleId: (json['ruleId'] as num?)?.toInt(),
      ruleVersion: (json['ruleVersion'] as num?)?.toInt(),
      eventId: (json['eventId'] as num?)?.toInt(),
      triggerType: json['triggerType'] as String?,
      status: parseRunStatus(json['status']),
      statusAt: json['statusAt'] == null ? null : parseRunStatus(json['statusAt']),
      scheduledFor: _instant(json['scheduledFor']),
      lateBySeconds: (json['lateBySeconds'] as num?)?.toInt(),
      entity: RunTarget.fromJson(json['entity']),
      requestedBy: RuleOwner.fromJson(json['requestedBy']),
      matched: n('matched'),
      done: n('done'),
      skipped: n('skipped'),
      failed: n('failed'),
      partial: json['partial'] as bool? ?? false,
      truncated: json['truncated'] as bool? ?? false,
      message: json['message'] as String?,
      createdAt: _instant(json['createdAt']),
      startedAt: _instant(json['startedAt']),
      finishedAt: _instant(json['finishedAt']),
    );
  }

  bool get inFlight => status == RunStatus.QUEUED || status == RunStatus.RUNNING;
}

/// What "Run now" answers (§6.14): the run it queued, or the identical one already waiting.
class QueuedRun {
  final int runId;
  final int? eventId;
  final RunStatus status;
  final DateTime? scheduledFor;

  const QueuedRun({required this.runId, required this.status, this.eventId, this.scheduledFor});

  factory QueuedRun.fromJson(Map<String, dynamic> json) => QueuedRun(
        runId: (json['runId'] as num).toInt(),
        eventId: (json['eventId'] as num?)?.toInt(),
        status: parseRunStatus(json['status']),
        scheduledFor: _instant(json['scheduledFor']),
      );
}

/// What one action did to one record in a run (§6.14 `EffectDto`). A target the viewer cannot see
/// carries only its type ([hidden]), and nothing that would name it.
class Effect {
  final int id;
  final String? actionKey;
  final String? actionType;
  final RunTarget? target;
  final String? status;
  final String? resultType;
  final int? resultId;
  final String? resultLink;
  final String? message;
  final List<String> warnings;
  final DateTime? createdAt;

  const Effect({
    required this.id,
    this.actionKey,
    this.actionType,
    this.target,
    this.status,
    this.resultType,
    this.resultId,
    this.resultLink,
    this.message,
    this.warnings = const [],
    this.createdAt,
  });

  factory Effect.fromJson(Map<String, dynamic> json) {
    final result = json['result'];
    return Effect(
      id: (json['id'] as num).toInt(),
      actionKey: json['actionKey'] as String?,
      actionType: json['actionType'] as String?,
      target: RunTarget.fromJson(json['target']),
      status: json['status'] as String?,
      resultType: result is Map ? result['type'] as String? : null,
      resultId: result is Map ? (result['id'] as num?)?.toInt() : null,
      resultLink: result is Map ? result['link'] as String? : null,
      message: json['message'] as String?,
      warnings: _strings(json['warnings']),
      createdAt: _instant(json['createdAt']),
    );
  }

  bool get hidden => target?.hidden ?? false;
}

/// One `{{…}}` a text may use (A4).
class PlaceholderItem {
  final String token;
  final String label;
  final String? kind;
  const PlaceholderItem({required this.token, required this.label, this.kind});

  /// The name inside the braces: `Invoice.Number` for `{{Invoice.Number}}`.
  String get key => token.replaceAll(RegExp(r'^\s*\{\{\s*|\s*\}\}\s*$'), '');

  factory PlaceholderItem.fromJson(Map<String, dynamic> json) => PlaceholderItem(
        token: json['token'] as String? ?? '',
        label: json['label'] as String? ?? json['token'] as String? ?? '',
        kind: json['kind'] as String?,
      );
}

/// The placeholders of one level: the customer's, the record's own, or the general ones.
class PlaceholderGroup {
  final String level;
  final String label;
  final List<PlaceholderItem> items;
  const PlaceholderGroup({required this.level, required this.label, required this.items});

  factory PlaceholderGroup.fromJson(Map<String, dynamic> json) => PlaceholderGroup(
        level: json['level'] as String? ?? '',
        label: json['label'] as String? ?? json['level'] as String? ?? '',
        items: [
          for (final i in (json['items'] as List?) ?? const [])
            if (i is Map) PlaceholderItem.fromJson(i.cast<String, dynamic>()),
        ].where((i) => i.token.isNotEmpty).toList(),
      );
}

/// Every placeholder offered for one kind of record (§6.14 placeholder catalogue).
class PlaceholderCatalogue {
  final String? entityType;
  final List<PlaceholderGroup> groups;
  const PlaceholderCatalogue({this.entityType, this.groups = const []});

  factory PlaceholderCatalogue.fromJson(Map<String, dynamic> json) => PlaceholderCatalogue(
        entityType: json['entityType'] as String?,
        groups: [
          for (final g in (json['groups'] as List?) ?? const [])
            if (g is Map) PlaceholderGroup.fromJson(g.cast<String, dynamic>()),
        ],
      );

  /// Every token offered, for telling a text's placeholders apart from ones that no longer apply.
  Set<String> get tokens => {
        for (final g in groups)
          for (final i in g.items) i.token
      };
}

/// One record in a preview's sample. With `evaluateAs=OWNER` a record the caller cannot see is
/// only [hidden].
class PreviewSample {
  final int? id;
  final String? label;
  final String? link;
  final bool hidden;
  final List<String> alreadyDone;

  const PreviewSample(
      {this.id, this.label, this.link, this.hidden = false, this.alreadyDone = const []});

  factory PreviewSample.fromJson(Map<String, dynamic> json) => PreviewSample(
        id: (json['id'] as num?)?.toInt(),
        label: json['label'] as String?,
        link: json['link'] as String?,
        hidden: json['hidden'] as bool? ?? false,
        alreadyDone: _strings(json['alreadyDone']),
      );
}

/// The record a preview looked at closely: whether it matches, and (live only) what each action
/// would do to it — kept as the server sent it for the action cards to render.
class PreviewRecord {
  final int? id;
  final String? label;
  final bool matches;
  final List<Map<String, dynamic>>? actions;

  const PreviewRecord({this.id, this.label, this.matches = false, this.actions});

  static PreviewRecord? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final actions = raw['actions'];
    return PreviewRecord(
      id: (raw['id'] as num?)?.toInt(),
      label: raw['label'] as String?,
      matches: raw['matches'] as bool? ?? false,
      actions: actions is List
          ? [
              for (final a in actions)
                if (a is Map) a.cast<String, dynamic>()
            ]
          : null,
    );
  }
}

/// What a rule would match (§6.14 `PreviewDto`).
class RulePreview {
  final String? entityType;
  final String? asOf;
  final String? asOfNote;
  final String? evaluatedAs;
  final int matchCount;
  final List<PreviewSample> sample;
  final PreviewRecord? record;
  final List<String> problems;
  final List<String> warnings;

  const RulePreview({
    this.entityType,
    this.asOf,
    this.asOfNote,
    this.evaluatedAs,
    this.matchCount = 0,
    this.sample = const [],
    this.record,
    this.problems = const [],
    this.warnings = const [],
  });

  factory RulePreview.fromJson(Map<String, dynamic> json) => RulePreview(
        entityType: json['entityType'] as String?,
        asOf: json['asOf'] as String?,
        asOfNote: json['asOfNote'] as String?,
        evaluatedAs: json['evaluatedAs'] as String?,
        matchCount: (json['matchCount'] as num?)?.toInt() ?? 0,
        sample: [
          for (final s in (json['sample'] as List?) ?? const [])
            if (s is Map) PreviewSample.fromJson(s.cast<String, dynamic>()),
        ],
        record: PreviewRecord.fromJson(json['record']),
        problems: _strings(json['problems']),
        warnings: _strings(json['warnings']),
      );
}

/// An outbox event the consumer gave up on (§6.14 `/health`).
class DeadEvent {
  final int id;
  final String? kind;
  final String? entityType;
  final int? entityId;
  final int? ruleId;
  final int attempts;
  final String? lastError;
  final DateTime? updatedAt;

  const DeadEvent({
    required this.id,
    this.kind,
    this.entityType,
    this.entityId,
    this.ruleId,
    this.attempts = 0,
    this.lastError,
    this.updatedAt,
  });

  factory DeadEvent.fromJson(Map<String, dynamic> json) => DeadEvent(
        id: (json['id'] as num).toInt(),
        kind: json['kind'] as String?,
        entityType: json['entityType'] as String?,
        entityId: (json['entityId'] as num?)?.toInt(),
        ruleId: (json['ruleId'] as num?)?.toInt(),
        attempts: (json['attempts'] as num?)?.toInt() ?? 0,
        lastError: json['lastError'] as String?,
        updatedAt: _instant(json['updatedAt']),
      );
}

/// How the automation consumer is keeping up (§6.14 `/health`, AUTOMATION_ADMIN only).
class AutomationHealth {
  final bool consumerEnabled;
  final int pending;
  final int processing;
  final int retrying;
  final int dead;
  final int? oldestPendingSeconds;
  final List<DeadEvent> deadEvents;

  const AutomationHealth({
    this.consumerEnabled = true,
    this.pending = 0,
    this.processing = 0,
    this.retrying = 0,
    this.dead = 0,
    this.oldestPendingSeconds,
    this.deadEvents = const [],
  });

  factory AutomationHealth.fromJson(Map<String, dynamic> json) {
    int n(String k) => (json[k] as num?)?.toInt() ?? 0;
    return AutomationHealth(
      consumerEnabled: json['consumerEnabled'] as bool? ?? true,
      pending: n('pending'),
      processing: n('processing'),
      retrying: n('retrying'),
      dead: n('dead'),
      oldestPendingSeconds: (json['oldestPendingSeconds'] as num?)?.toInt(),
      deadEvents: [
        for (final e in (json['deadEvents'] as List?) ?? const [])
          if (e is Map && e['id'] is num) DeadEvent.fromJson(e.cast<String, dynamic>()),
      ],
    );
  }
}
