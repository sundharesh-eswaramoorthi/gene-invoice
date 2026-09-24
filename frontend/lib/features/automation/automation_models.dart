import 'package:flutter/foundation.dart';

import '../../core/table/table_models.dart';
import '../../shared/models/privileges.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';

/// What a rule can be written about (A1).
///
/// Three constants and deliberately not [EmailEntityType], which also carries products, users and
/// roles: a rule's subject has to be something with a customer behind it, because that is what
/// gives a rule its branch, its POC book and its placeholders. The server's own `SubjectType` is
/// its own enum for exactly that reason, and this mirrors it (A1).
enum AutomationSubject {
  customer,
  invoice,
  payment;

  String get wire => name.toUpperCase();

  String get noun => name;

  String get plural => '${name}s';

  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  String get routeBase => '/$plural';

  String link(int id) => '$routeBase/$id';

  /// The table schema a condition on this subject is written against — the SAME registered schema
  /// the server validates the rule with, so a chip the builder composes is one the server accepts
  /// (A2).
  String get tableEntity => plural;

  /// What the reference picker calls this kind of record, for the sample rows a dry run answers
  /// with and for the preview's record picker.
  String get referenceKind => name;

  /// What somebody must hold to open a record of this kind. A step names the record it acted on,
  /// and offering a link to a page the router would bounce them straight off is worse than plain
  /// text (A5, UI-10).
  String get recordViewPrivilege => switch (this) {
        customer => Privileges.customerView,
        invoice => Privileges.invoiceView,
        payment => Privileges.paymentView,
      };

  /// The email layer's name for the same record, which is what
  /// `GET /api/emails/context?entityType=` reads (A3).
  EmailEntityType get emailType => switch (this) {
        customer => EmailEntityType.customer,
        invoice => EmailEntityType.invoice,
        payment => EmailEntityType.payment,
      };

  static AutomationSubject? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// When a rule is armed: by a change to a record, or by the clock (A1).
enum AutomationTrigger {
  ON_CREATED,
  ON_UPDATED,
  ON_CREATED_OR_UPDATED,
  SCHEDULE_DAILY,
  SCHEDULE_WEEKLY;

  bool get scheduled => this == SCHEDULE_DAILY || this == SCHEDULE_WEEKLY;

  bool get weekly => this == SCHEDULE_WEEKLY;

  String get label => switch (this) {
        ON_CREATED => 'When created',
        ON_UPDATED => 'When changed',
        ON_CREATED_OR_UPDATED => 'When created or changed',
        SCHEDULE_DAILY => 'Every day',
        SCHEDULE_WEEKLY => 'Every week',
      };
}

AutomationTrigger parseTrigger(String? raw) => AutomationTrigger.values
    .firstWhere((t) => t.name == raw, orElse: () => AutomationTrigger.ON_CREATED_OR_UPDATED);

/// The four things a rule can do. The vocabulary is CLOSED: every one of them is a write somebody
/// already had a screen for (A3).
enum AutomationActionKind {
  CREATE_TASK,
  CREATE_PROMISE,
  CREATE_DISPUTE,
  SEND_EMAIL;

  String get label => switch (this) {
        CREATE_TASK => 'Create a task',
        CREATE_PROMISE => 'Create a promise to pay',
        CREATE_DISPUTE => 'Raise a dispute',
        SEND_EMAIL => 'Send an email',
      };

  /// A dispute needs something to dispute, so it is offered only where the server accepts it —
  /// the same rule `validateCreateDispute` enforces, said before the save rather than after (A3).
  bool offeredOn(AutomationSubject subject) => switch (this) {
        CREATE_DISPUTE =>
          subject == AutomationSubject.invoice || subject == AutomationSubject.payment,
        _ => true,
      };
}

AutomationActionKind? parseActionKind(String? raw) {
  for (final k in AutomationActionKind.values) {
    if (k.name == raw) return k;
  }
  return null;
}

/// Where a promised amount comes from. FIXED is the only one that reads a figure (A3).
enum AmountSource {
  INVOICE_BALANCE,
  CUSTOMER_OUTSTANDING,
  FIXED;

  String get label => switch (this) {
        INVOICE_BALANCE => "The invoice's balance",
        CUSTOMER_OUTSTANDING => "The customer's outstanding",
        FIXED => 'A fixed amount',
      };

  /// INVOICE_BALANCE only means something on an invoice rule, which is what the server refuses at
  /// save; the dropdown leaves it out rather than earning that 400 (A3).
  bool offeredOn(AutomationSubject subject) =>
      this != INVOICE_BALANCE || subject == AutomationSubject.invoice;
}

AmountSource parseAmountSource(String? raw) =>
    AmountSource.values.firstWhere((a) => a.name == raw, orElse: () => AmountSource.FIXED);

/// Where one unit of work has got to (A5).
///
/// SKIPPED and POISONED are both terminal and mean different things: SKIPPED is "this correctly
/// did not happen" and POISONED is "this kept failing and nobody is going to try again".
enum StepStatus {
  QUEUED,
  RUNNING,
  DONE,
  SKIPPED,
  POISONED;

  bool get terminal => this == DONE || this == SKIPPED || this == POISONED;

  bool get live => this == QUEUED || this == RUNNING;

  String get label => switch (this) {
        QUEUED => 'Queued',
        RUNNING => 'Running',
        DONE => 'Done',
        SKIPPED => 'Skipped',
        POISONED => 'Failed',
      };
}

/// An unknown constant reads as QUEUED, the house convention every other status parser follows:
/// the row still appears, and every control is gated on the server's answer to the write (A5).
StepStatus parseStepStatus(String? raw) =>
    StepStatus.values.firstWhere((s) => s.name == raw, orElse: () => StepStatus.QUEUED);

/// Which door a step came in through (A5).
enum StepSource {
  EVENT,
  SCHEDULE,
  MANUAL;

  String get label => switch (this) {
        EVENT => 'Record changed',
        SCHEDULE => 'Schedule',
        MANUAL => 'Run now',
      };
}

StepSource parseStepSource(String? raw) =>
    StepSource.values.firstWhere((s) => s.name == raw, orElse: () => StepSource.EVENT);

/// Where one scheduled or manual run has got to (A5).
enum RunStatus {
  FANNING,
  RUNNING,
  DONE,
  FAILED,
  SKIPPED_OVERRUN;

  bool get live => this == FANNING || this == RUNNING;

  String get label => switch (this) {
        FANNING => 'Finding records',
        RUNNING => 'Running',
        DONE => 'Finished',
        FAILED => 'Failed',
        SKIPPED_OVERRUN => 'Skipped — the last run had not finished',
      };
}

RunStatus parseRunStatus(String? raw) =>
    RunStatus.values.firstWhere((s) => s.name == raw, orElse: () => RunStatus.FANNING);

/// What a finished step actually made (A5).
///
/// PENDING_CHANGE is a SUCCESS and not a failure: an action over its region's approval threshold
/// is parked, and the honest answer is "made a change that is waiting for somebody". It therefore
/// wears the same successful colour and links to the approvals queue (A5, B2 INTEGRATION).
enum ProducedType {
  TASK,
  PROMISE,
  DISPUTE,
  EMAIL,
  PENDING_CHANGE;

  String get label => switch (this) {
        TASK => 'Task',
        PROMISE => 'Promise',
        DISPUTE => 'Dispute',
        EMAIL => 'Email',
        PENDING_CHANGE => 'Awaiting approval',
      };

  /// Where what a step made now lives, in this app's own route space. EMAIL is the one without a
  /// page of its own: an email is read in its record's Email tab, and there is no /emails/7 to
  /// send anybody to (A5).
  String? link(int id) => switch (this) {
        TASK => '/tasks/$id',
        PROMISE => '/promises/$id',
        DISPUTE => '/disputes/$id',
        PENDING_CHANGE => '/approvals/$id',
        EMAIL => null,
      };

  /// What somebody must hold to open it, so the link is offered only where it would work (UI-10).
  String? get viewPrivilege => switch (this) {
        TASK => Privileges.taskView,
        PROMISE => Privileges.promiseView,
        DISPUTE => Privileges.disputeView,
        PENDING_CHANGE => Privileges.approvalView,
        EMAIL => null,
      };
}

ProducedType? parseProducedType(String? raw) {
  for (final p in ProducedType.values) {
    if (p.name == raw) return p;
  }
  return null;
}

// ---------------------------------------------------------------- the condition tree (A2)

enum Connector {
  AND,
  OR;

  String get label => this == AND ? 'All of' : 'Any of';

  String get wire => name;
}

/// One node of a rule's condition tree, in the EXACT wire shape `ConditionJson` reads and writes:
/// `{"filter":"balance:gt:50000"}` for a leaf and `{"op":"OR","of":[...]}` for a group (A2).
///
/// THIS IS ITS OWN MODEL AND NOT A [TableQuery]. `TableQuery.addFilter` de-dupes by
/// (field, operator), which would silently eat `balance:gt:1000 OR balance:gt:5000` — the very
/// thing an OR exists to express. A tree keeps every leaf it is given (A2).
@immutable
sealed class ConditionNode {
  const ConditionNode();

  Map<String, dynamic> toJson();

  /// How many leaves the whole subtree holds, against the server's MAX_LEAVES.
  int get leafCount;

  /// How deep the subtree goes, counting this node as one, against the server's MAX_DEPTH.
  int get depth;

  /// Null for anything that is neither a filter nor a group, which is what a rule with no
  /// conditions comes back as (A2).
  static ConditionNode? fromJson(Object? json) {
    if (json is! Map) return null;
    final map = json.cast<String, dynamic>();
    final filter = map['filter'];
    if (filter is String) {
      final parsed = TableFilter.parse(filter);
      return parsed == null ? null : ConditionLeaf(parsed);
    }
    final op = map['op'];
    if (op is String) {
      return ConditionGroup(
        op.toUpperCase() == 'OR' ? Connector.OR : Connector.AND,
        [
          for (final child in (map['of'] as List?) ?? const [])
            if (fromJson(child) case final node?) node,
        ],
      );
    }
    return null;
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
  int get depth => 1;
}

@immutable
class ConditionGroup extends ConditionNode {
  final Connector op;
  final List<ConditionNode> of;

  const ConditionGroup(this.op, this.of);

  @override
  Map<String, dynamic> toJson() => {
        'op': op.wire,
        'of': [for (final child in of) child.toJson()],
      };

  @override
  int get leafCount => of.fold(0, (sum, child) => sum + child.leafCount);

  @override
  int get depth => 1 + of.fold(0, (deepest, child) => child.depth > deepest ? child.depth : deepest);

  ConditionGroup withChildren(List<ConditionNode> children) => ConditionGroup(op, children);

  ConditionGroup withOp(Connector next) => ConditionGroup(next, of);

  /// The same tree with every EMPTY group taken out.
  ///
  /// The server accepts an empty TOP-LEVEL group — it means "every record" — and refuses an empty
  /// NESTED one with "A group needs at least one condition". Somebody who pressed "Add group" and
  /// then changed their mind should not have their save refused for a card they can see is blank,
  /// so a group with nothing in it is dropped on the way out rather than sent (A2).
  ConditionGroup get pruned {
    final kept = <ConditionNode>[];
    for (final child in of) {
      if (child is ConditionGroup) {
        final inner = child.pruned;
        if (inner.leafCount > 0) kept.add(inner);
      } else {
        kept.add(child);
      }
    }
    return ConditionGroup(op, kept);
  }
}

/// The server's own caps, which no endpoint publishes — `Conditions.MAX_DEPTH` and
/// `MAX_LEAVES`. They are here so the builder refuses what the save would refuse, in the same
/// words; if an automation settings endpoint ever publishes them, read them from there instead of
/// keeping two copies (A2).
const int conditionMaxDepth = 5;
const int conditionMaxLeaves = 40;

/// THE DEEPEST A GROUP CARD MAY SIT, which is one shallower than [conditionMaxDepth] — and that
/// subtraction is the whole point of naming it.
///
/// `Conditions.walk` starts the root group at depth 1 and charges every child its own level,
/// INCLUDING a leaf: a condition inside a group at depth D is walked at D + 1. So the deepest
/// group that may hold anything at all is at [conditionMaxDepth] - 1, and a group at
/// [conditionMaxDepth] can hold neither a condition (walked at 6, "Conditions may not be nested
/// more than 5 deep") nor an empty group ("A group needs at least one condition") — it is a card
/// with no legal contents. The builder used to offer exactly that level, so four presses of "Add
/// group" led to a dead end and a 400 that could not say which card was the illegal one (A2).
const int conditionMaxGroupDepth = conditionMaxDepth - 1;

// ---------------------------------------------------------------- what a rule does (A3)

/// One action, in the editor's own mutable shape.
///
/// A record would be tidier and a [ReorderableListView] of them is not: the action editor moves,
/// adds and edits them in place, and the whole list is posted back on save. `kind` is the wire
/// discriminator `@JsonTypeInfo` writes, and the fields a kind does not use are simply left out
/// of [toJson] so a saved rule carries nothing it does not mean (A3).
class AutomationAction {
  AutomationActionKind kind;

  // CREATE_TASK
  String title;
  String notes;
  List<EmailToken> assignees;
  int? dueInDays;

  // CREATE_PROMISE
  AmountSource amountFrom;
  String fixedAmount;
  int? promisedInDays;
  EmailToken? collectionPoc;

  // CREATE_DISPUTE
  String reason;

  // SEND_EMAIL
  EmailToken? from;
  List<EmailToken> to;
  String subject;
  String body;

  AutomationAction({
    required this.kind,
    this.title = '',
    this.notes = '',
    List<EmailToken>? assignees,
    this.dueInDays,
    this.amountFrom = AmountSource.FIXED,
    this.fixedAmount = '',
    this.promisedInDays,
    this.collectionPoc,
    this.reason = '',
    this.from,
    List<EmailToken>? to,
    this.subject = '',
    this.body = '',
  })  : assignees = assignees ?? [],
        to = to ?? [];

  factory AutomationAction.fromJson(Map<String, dynamic> json) {
    final kind = parseActionKind(json['kind'] as String?) ?? AutomationActionKind.CREATE_TASK;
    return AutomationAction(
      kind: kind,
      title: json['title'] as String? ?? '',
      notes: json['notes'] as String? ?? '',
      assignees: _tokens(json['assignees']),
      dueInDays: (json['dueInDays'] as num?)?.toInt(),
      amountFrom: parseAmountSource(json['amountFrom'] as String?),
      fixedAmount: json['fixedAmount'] == null ? '' : '${json['fixedAmount']}',
      promisedInDays: (json['promisedInDays'] as num?)?.toInt(),
      collectionPoc: _token(json['collectionPoc']),
      reason: json['reason'] as String? ?? '',
      from: _token(json['from']),
      to: _tokens(json['to']),
      subject: json['subject'] as String? ?? '',
      body: json['body'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() => switch (kind) {
        AutomationActionKind.CREATE_TASK => {
            'kind': kind.name,
            'title': title,
            if (notes.trim().isNotEmpty) 'notes': notes,
            'assignees': [for (final t in assignees) t.toJson()],
            if (dueInDays != null) 'dueInDays': dueInDays,
          },
        AutomationActionKind.CREATE_PROMISE => {
            'kind': kind.name,
            'amountFrom': amountFrom.name,
            if (amountFrom == AmountSource.FIXED && fixedAmount.trim().isNotEmpty)
              'fixedAmount': fixedAmount.trim(),
            if (promisedInDays != null) 'promisedInDays': promisedInDays,
            if (collectionPoc != null) 'collectionPoc': collectionPoc!.toJson(),
            if (notes.trim().isNotEmpty) 'notes': notes,
          },
        AutomationActionKind.CREATE_DISPUTE => {'kind': kind.name, 'reason': reason},
        AutomationActionKind.SEND_EMAIL => {
            'kind': kind.name,
            if (from != null) 'from': from!.toJson(),
            'to': [for (final t in to) t.toJson()],
            'subject': subject,
            if (body.trim().isNotEmpty) 'body': body,
          },
      };

  /// A fresh copy, because an action is MUTABLE and the builder edits it in place.
  ///
  /// The rule the detail provider is holding must not change under somebody who opened the editor
  /// and then backed out: without this, typing in the form would rewrite the cached DTO and the
  /// "What it does" tab would show edits that were never saved (A3).
  AutomationAction copy() => AutomationAction(
        kind: kind,
        title: title,
        notes: notes,
        assignees: [...assignees],
        dueInDays: dueInDays,
        amountFrom: amountFrom,
        fixedAmount: fixedAmount,
        promisedInDays: promisedInDays,
        collectionPoc: collectionPoc,
        reason: reason,
        from: from,
        to: [...to],
        subject: subject,
        body: body,
      );

  /// Nothing has been filled in yet. What "changing the subject clears your work" asks about:
  /// a blank action somebody has not touched is not work, and confirming its loss would train
  /// people to click through the confirm that matters (A1, A3).
  bool get isBlank => switch (kind) {
        AutomationActionKind.CREATE_TASK => title.trim().isEmpty &&
            notes.trim().isEmpty &&
            assignees.isEmpty &&
            dueInDays == null,
        AutomationActionKind.CREATE_PROMISE => fixedAmount.trim().isEmpty &&
            promisedInDays == null &&
            collectionPoc == null &&
            notes.trim().isEmpty,
        AutomationActionKind.CREATE_DISPUTE => reason.trim().isEmpty,
        AutomationActionKind.SEND_EMAIL => from == null &&
            to.isEmpty &&
            subject.trim().isEmpty &&
            body.trim().isEmpty,
      };

  /// One line for the collapsed card, so a rule with five actions reads as a list of sentences
  /// rather than five identical headings (A3).
  String get summary => switch (kind) {
        AutomationActionKind.CREATE_TASK =>
          title.trim().isEmpty ? 'No title yet' : title.trim(),
        AutomationActionKind.CREATE_PROMISE => amountFrom.label,
        AutomationActionKind.CREATE_DISPUTE =>
          reason.trim().isEmpty ? 'No reason yet' : reason.trim(),
        AutomationActionKind.SEND_EMAIL =>
          subject.trim().isEmpty ? 'No subject yet' : subject.trim(),
      };

  static List<EmailToken> _tokens(Object? raw) => [
        for (final item in (raw as List?) ?? const [])
          if (_token(item) case final token?) token,
      ];

  /// Defensively: `EmailToken.fromJson` reads `type` as a non-null String, so a token written by
  /// a server that later drops the field would throw inside a list page rather than losing one
  /// chip. A token with no type is not a token.
  static EmailToken? _token(Object? raw) => raw is Map && raw['type'] is String
      ? EmailToken.fromJson(raw.cast<String, dynamic>())
      : null;
}

/// A branch a rule names (A1, B1).
@immutable
class RuleRegion {
  final int id;
  final String code;
  final String name;

  const RuleRegion({required this.id, required this.code, required this.name});

  factory RuleRegion.fromJson(Map<String, dynamic> json) => RuleRegion(
        id: (json['id'] as num).toInt(),
        code: json['code'] as String? ?? '',
        name: json['name'] as String? ?? '',
      );

  String get display => name.isEmpty ? code : name;
}

/// One rule, over the exact RuleDto wire shape. Unknown keys are ignored, the house rule that
/// makes every later backend field wire-compatible with the shipped client (A1).
class AutomationRule {
  final int id;
  final String name;
  final String? description;
  final AutomationSubject? subjectType;
  final AutomationTrigger triggerKind;
  final int? scheduleHourUtc;
  final int? scheduleDayOfWeek;

  /// The JSON TREE, not a string — the condition editor reads it as structure. It comes back
  /// canonicalised, so a saved condition and a typed filter chip are one grammar (A2).
  final ConditionNode? conditions;
  final List<AutomationAction> actions;
  final int? cooldownDays;
  final bool enabled;
  final int definitionVersion;

  /// What the rule NAMED. Empty is a real and different state, which [allAuthorRegions] says in
  /// a word: "every branch my author may manage, as of now" (A1, B1).
  final List<RuleRegion> regions;
  final bool allAuthorRegions;

  final DateTime? nextRunAt;
  final DateTime? lastRunAt;
  final int? createdByUserId;
  final String? createdByName;
  final DateTime? createdAt;
  final DateTime? updatedAt;
  final int? version;

  const AutomationRule({
    required this.id,
    required this.name,
    this.description,
    this.subjectType,
    this.triggerKind = AutomationTrigger.ON_CREATED_OR_UPDATED,
    this.scheduleHourUtc,
    this.scheduleDayOfWeek,
    this.conditions,
    this.actions = const [],
    this.cooldownDays,
    this.enabled = true,
    this.definitionVersion = 1,
    this.regions = const [],
    this.allAuthorRegions = true,
    this.nextRunAt,
    this.lastRunAt,
    this.createdByUserId,
    this.createdByName,
    this.createdAt,
    this.updatedAt,
    this.version,
  });

  factory AutomationRule.fromJson(Map<String, dynamic> json) => AutomationRule(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String? ?? '',
        description: json['description'] as String?,
        subjectType: AutomationSubject.fromWire(json['subjectType'] as String?),
        triggerKind: parseTrigger(json['triggerKind'] as String?),
        scheduleHourUtc: (json['scheduleHourUtc'] as num?)?.toInt(),
        scheduleDayOfWeek: (json['scheduleDayOfWeek'] as num?)?.toInt(),
        conditions: ConditionNode.fromJson(json['conditions']),
        actions: ((json['actions'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => AutomationAction.fromJson(m.cast<String, dynamic>()))
            .toList(),
        cooldownDays: (json['cooldownDays'] as num?)?.toInt(),
        enabled: json['enabled'] as bool? ?? true,
        definitionVersion: (json['definitionVersion'] as num?)?.toInt() ?? 1,
        regions: ((json['regions'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => RuleRegion.fromJson(m.cast<String, dynamic>()))
            .toList(),
        allAuthorRegions: json['allAuthorRegions'] as bool? ?? true,
        nextRunAt: _time(json['nextRunAt']),
        lastRunAt: _time(json['lastRunAt']),
        createdByUserId: (json['createdByUserId'] as num?)?.toInt(),
        createdByName: json['createdByName'] as String?,
        createdAt: _time(json['createdAt']),
        updatedAt: _time(json['updatedAt']),
        version: (json['version'] as num?)?.toInt(),
      );

  /// Where the rule reaches, in words. An empty [regions] is not "nowhere" (A1, B1).
  String get reachLabel => allAuthorRegions || regions.isEmpty
      ? 'Every branch its author manages'
      : regions.map((r) => r.display).join(', ');
}

/// One row of the run history (A5).
class AutomationStep {
  final int id;
  final DateTime? createdAt;
  final int? ruleId;
  final String ruleName;
  final int ruleVersion;
  final AutomationSubject? subjectType;
  final int? subjectId;
  final int? customerId;
  final String? customerName;
  final AutomationActionKind? actionKind;
  final int actionIndex;
  final StepSource source;
  final StepStatus status;
  final int attempts;
  final int? runId;
  final String? occasion;
  final ProducedType? producedType;
  final int? producedId;

  /// A ', '-joined list of role labels and placeholder tokens that had nobody or nothing behind
  /// them. The server writes it to be read, so it is rendered as it arrives (A5, A4).
  final String? unresolved;

  /// A real sentence: 'Task #12', a skip reason verbatim, the error text, or
  /// 'Waiting for approval (change #N): …'. Rendered as-is for the same reason (A5).
  final String? result;
  final DateTime? finishedAt;
  final int? regionId;
  final String? regionName;

  const AutomationStep({
    required this.id,
    this.createdAt,
    this.ruleId,
    this.ruleName = '',
    this.ruleVersion = 0,
    this.subjectType,
    this.subjectId,
    this.customerId,
    this.customerName,
    this.actionKind,
    this.actionIndex = 0,
    this.source = StepSource.EVENT,
    this.status = StepStatus.QUEUED,
    this.attempts = 0,
    this.runId,
    this.occasion,
    this.producedType,
    this.producedId,
    this.unresolved,
    this.result,
    this.finishedAt,
    this.regionId,
    this.regionName,
  });

  factory AutomationStep.fromJson(Map<String, dynamic> json) => AutomationStep(
        id: (json['id'] as num).toInt(),
        createdAt: _time(json['createdAt']),
        ruleId: (json['ruleId'] as num?)?.toInt(),
        ruleName: json['ruleName'] as String? ?? '',
        ruleVersion: (json['ruleVersion'] as num?)?.toInt() ?? 0,
        subjectType: AutomationSubject.fromWire(json['subjectType'] as String?),
        subjectId: (json['subjectId'] as num?)?.toInt(),
        customerId: (json['customerId'] as num?)?.toInt(),
        customerName: json['customerName'] as String?,
        actionKind: parseActionKind(json['actionKind'] as String?),
        actionIndex: (json['actionIndex'] as num?)?.toInt() ?? 0,
        source: parseStepSource(json['source'] as String?),
        status: parseStepStatus(json['status'] as String?),
        attempts: (json['attempts'] as num?)?.toInt() ?? 0,
        runId: (json['runId'] as num?)?.toInt(),
        occasion: json['occasion'] as String?,
        producedType: parseProducedType(json['producedType'] as String?),
        producedId: (json['producedId'] as num?)?.toInt(),
        unresolved: json['unresolved'] as String?,
        result: json['result'] as String?,
        finishedAt: _time(json['finishedAt']),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
      );

  /// Where the record this step acted on lives, or null when the server named neither.
  String? get recordLink =>
      subjectType == null || subjectId == null ? null : subjectType!.link(subjectId!);

  String get recordLabel => subjectType == null
      ? 'Record #${subjectId ?? '—'}'
      : '${subjectType!.label} #${subjectId ?? '—'}';

  /// Where what this step made now lives, or null when it made nothing reachable.
  String? get producedLink =>
      producedType == null || producedId == null ? null : producedType!.link(producedId!);

  String? get producedLabel =>
      producedType == null ? null : '${producedType!.label} #${producedId ?? '—'}';

  /// Retry is offered on a step that stopped rather than one that finished — the same pair the
  /// server accepts (A5).
  bool get retryable => status == StepStatus.POISONED || status == StepStatus.SKIPPED;
}

/// One firing, as `POST /rules/{id}/run?apply=true` answers it (A5).
class AutomationRun {
  final int id;
  final int? ruleId;
  final String? ruleName;
  final StepSource source;
  final String? occasion;
  final RunStatus status;
  final int matched;
  final bool truncated;
  final int stepsPlanned;
  final DateTime? startedAt;
  final DateTime? finishedAt;
  final String? error;

  const AutomationRun({
    required this.id,
    this.ruleId,
    this.ruleName,
    this.source = StepSource.MANUAL,
    this.occasion,
    this.status = RunStatus.FANNING,
    this.matched = 0,
    this.truncated = false,
    this.stepsPlanned = 0,
    this.startedAt,
    this.finishedAt,
    this.error,
  });

  factory AutomationRun.fromJson(Map<String, dynamic> json) => AutomationRun(
        id: (json['id'] as num).toInt(),
        ruleId: (json['ruleId'] as num?)?.toInt(),
        ruleName: json['ruleName'] as String?,
        source: parseStepSource(json['source'] as String?),
        occasion: json['occasion'] as String?,
        status: parseRunStatus(json['status'] as String?),
        matched: (json['matched'] as num?)?.toInt() ?? 0,
        truncated: json['truncated'] as bool? ?? false,
        stepsPlanned: (json['stepsPlanned'] as num?)?.toInt() ?? 0,
        startedAt: _time(json['startedAt']),
        finishedAt: _time(json['finishedAt']),
        error: json['error'] as String?,
      );
}

/// One record a dry run would act on, as THE PERSON WHO ASKED would see it. A record inside the
/// rule's reach but outside the caller's own is counted in `matched` and left out of the sample
/// (A5, AUTH-08).
class DryRunSample {
  final int id;
  final String label;
  final String? renderedTitle;
  final String? renderedSubject;

  const DryRunSample({
    required this.id,
    required this.label,
    this.renderedTitle,
    this.renderedSubject,
  });

  factory DryRunSample.fromJson(Map<String, dynamic> json) => DryRunSample(
        id: (json['id'] as num?)?.toInt() ?? 0,
        label: json['label'] as String? ?? '',
        renderedTitle: json['renderedTitle'] as String?,
        renderedSubject: json['renderedSubject'] as String?,
      );
}

/// What would happen, having written nothing at all (A5).
class DryRun {
  final int matched;
  final bool truncated;
  final List<DryRunSample> sample;

  const DryRun({this.matched = 0, this.truncated = false, this.sample = const []});

  factory DryRun.fromJson(Map<String, dynamic> json) => DryRun(
        matched: (json['matched'] as num?)?.toInt() ?? 0,
        truncated: json['truncated'] as bool? ?? false,
        sample: ((json['sample'] as List?) ?? const [])
            .whereType<Map>()
            .map((m) => DryRunSample.fromJson(m.cast<String, dynamic>()))
            .toList(),
      );
}

/// One offered placeholder. `key` is the FULL token including its braces, which is what the
/// picker inserts (A4).
class PlaceholderSlot {
  final String key;
  final String label;
  final String group;
  final String? type;
  final String? example;

  const PlaceholderSlot({
    required this.key,
    required this.label,
    required this.group,
    this.type,
    this.example,
  });

  factory PlaceholderSlot.fromJson(Map<String, dynamic> json) => PlaceholderSlot(
        key: json['key'] as String? ?? '',
        label: json['label'] as String? ?? '',
        group: json['group'] as String? ?? 'Other',
        type: json['type'] as String?,
        example: json['example'] as String?,
      );
}

/// One rendered template plus the tokens that had nothing behind them on this record (A4).
class RenderedText {
  final String text;
  final List<String> unresolved;

  const RenderedText({this.text = '', this.unresolved = const []});

  factory RenderedText.fromJson(Map<String, dynamic> json) => RenderedText(
        text: json['text'] as String? ?? '',
        unresolved: ((json['unresolved'] as List?) ?? const []).map((e) => '$e').toList(),
      );
}

/// What `POST /api/automation/preview` answers. `asOf` is stated rather than assumed: until
/// temporal storage exists the renderer reads the LIVE record, so the panel says so rather than
/// pretending to be historical (A4, B3).
class RulePreview {
  final AutomationSubject? subjectType;
  final int? subjectId;
  final String? subjectLabel;
  final DateTime? asOf;
  final RenderedText? title;
  final RenderedText? subject;
  final RenderedText? body;

  const RulePreview({
    this.subjectType,
    this.subjectId,
    this.subjectLabel,
    this.asOf,
    this.title,
    this.subject,
    this.body,
  });

  factory RulePreview.fromJson(Map<String, dynamic> json) => RulePreview(
        subjectType: AutomationSubject.fromWire(json['subjectType'] as String?),
        subjectId: (json['subjectId'] as num?)?.toInt(),
        subjectLabel: json['subjectLabel'] as String?,
        asOf: _time(json['asOf']),
        title: _rendered(json['title']),
        subject: _rendered(json['subject']),
        body: _rendered(json['body']),
      );

  static RenderedText? _rendered(Object? raw) =>
      raw is Map ? RenderedText.fromJson(raw.cast<String, dynamic>()) : null;
}

/// How often the run history should ask again, computed the way `emailRefreshInterval` is: 5 s
/// while anything is still being worked, 30 s for a minute after the last one settled, and null
/// once there is nothing left to watch (A5).
///
/// Null is what STOPS the timer, which is the whole point: a settled list must not go on polling
/// an endpoint for ever because somebody left the tab open.
Duration? automationRefreshInterval(Iterable<AutomationStep> steps, {DateTime? now}) {
  final since = (now ?? DateTime.now()).subtract(const Duration(minutes: 1));
  var justSettled = false;
  for (final step in steps) {
    if (step.status.live) return const Duration(seconds: 5);
    final finished = step.finishedAt;
    if (finished != null && finished.isAfter(since)) justSettled = true;
  }
  return justSettled ? const Duration(seconds: 30) : null;
}

DateTime? _time(Object? raw) => raw == null ? null : DateTime.tryParse('$raw');
