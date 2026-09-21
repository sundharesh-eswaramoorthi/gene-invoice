import 'package:flutter/foundation.dart';

import '../../core/format.dart';
// describeFilter lives with the filter editor because it is the other half of it: the words a
// chip shows are the words that editor wrote. A rule's WHERE is those very chips (R9), so it is
// read back with the same function rather than a second one that could drift from it.
import '../../core/table/filter_editor.dart';
import '../../core/table/table_models.dart';
import '../email/email_entity.dart';
import '../email/email_models.dart';

/// A rule reads "WHEN … WHERE … THEN …" (R1): when something happens to a kind of record, and the
/// record matches the filters, do one of four things to it. These models mirror `AutomationDtos`
/// field for field; nothing is renamed on the way in, so a change on the server shows up here as a
/// compile error rather than as a field that quietly reads null.
///
/// The three enums below are the app's own copies of the server's closed sets. They are nullable
/// wherever they are read off the wire: a value this build does not know means the server is newer
/// than the app, and a rule that cannot be understood is still worth listing — the server sends
/// its own words for the WHEN and the THEN, so such a row still reads correctly even here.

/// What the columns behind a rule hold, mirroring the server's own constants, so a field stops
/// taking input where the server would refuse it. They live here rather than in `FieldLimits`
/// because they are this feature's: `AutomationRule`, `AutomationService` and the records each
/// action writes into. A limit the form does not enforce is left to the server's own message.
abstract final class AutomationLimits {
  /// `AutomationRule.NAME_MAX` / `DESCRIPTION_MAX`.
  static const name = 200;
  static const description = 1000;

  /// `AutomationService.MAX_FILTERS`: more than this on one rule is somebody building a query
  /// rather than a rule, and every one is evaluated against every candidate on every run.
  static const maxFilters = 20;

  /// `AutomationService.MAX_DUE_IN_DAYS`: further out than this is almost certainly a typed
  /// extra digit.
  static const maxDueInDays = 3650;

  /// `Task.TITLE_MAX` / `Task.NOTES_MAX`, and `FieldLimits.DISPUTE_TEXT`, for the records the
  /// THEN writes into.
  static const taskTitle = 200;
  static const taskNotes = 2000;
  static const disputeReason = 2000;
}

/// The kinds of record a rule can watch (`AutomationEntityType`). Deliberately narrower than the
/// records email can be about: these three are the ones people create, edit and already filter
/// lists of, and the WHERE half of a rule is exactly those list filters (R9).
enum AutomationRecordType {
  customer,
  invoice,
  payment;

  String get wire => name.toUpperCase();

  /// 'Invoice', for a label.
  String get label => '${name[0].toUpperCase()}${name.substring(1)}';

  /// 'invoice', for a sentence: "When an invoice is created".
  String get noun => name;

  /// "an invoice", "a payment" — the article the noun takes in the editor's sentences.
  String get nounWithArticle => name == 'invoice' ? 'an $noun' : 'a $noun';

  /// The table whose schema the WHERE is built from — the very one the list page filters by, so a
  /// rule can say nothing the user could not have typed into that filter bar (R9).
  String get tableEntity => '${name}s';

  /// The same kind as the rest of the app names it. Tasks, assignees and email all key off
  /// [EmailEntityType], so a rule's people are picked against that rather than a second enum.
  EmailEntityType get emailType => switch (this) {
        customer => EmailEntityType.customer,
        invoice => EmailEntityType.invoice,
        payment => EmailEntityType.payment,
      };

  static AutomationRecordType? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// What sets a rule off (`TriggerKind`). The first two are something a person did to a record; the
/// last two are the clock.
enum AutomationTrigger {
  created,
  updated,
  daily,
  weekly;

  String get wire => name.toUpperCase();

  /// How the WHEN picker offers it. The rules list shows the server's own `triggerLabel` instead,
  /// so one place decides how a saved rule reads and this is only for the rule being written.
  String get label => switch (this) {
        created => 'When it is created',
        updated => 'When it is updated',
        daily => 'Every day',
        weekly => 'Every week',
      };

  /// True for the two the clock raises. They sweep every record that matches rather than reacting
  /// to one, which is worth saying on the form: a filter that matches nothing does nothing, and a
  /// filter that matches everything does it to everything.
  bool get isScheduled => this == daily || this == weekly;

  static AutomationTrigger? fromWire(String? wire) {
    for (final t in values) {
      if (t.wire == wire) return t;
    }
    return null;
  }
}

/// The four things a rule may do (`ActionType`). Each is something a person can already do by hand
/// from the record, done through the same service they would use.
enum AutomationAction {
  createTask,
  createPromise,
  createDispute,
  sendEmail;

  String get wire => switch (this) {
        createTask => 'CREATE_TASK',
        createPromise => 'CREATE_PROMISE',
        createDispute => 'CREATE_DISPUTE',
        sendEmail => 'SEND_EMAIL',
      };

  String get label => switch (this) {
        createTask => 'Create a task',
        createPromise => 'Record a promise',
        createDispute => 'Raise a dispute',
        sendEmail => 'Send an email',
      };

  /// Whose people the assignees or recipients are picked against — exactly what
  /// `AutomationService.checkAction` checks them against, so the picker offers the roles the
  /// server will accept and no others. A task and an email hang off the record the rule watches;
  /// a promise and a dispute are records of their own.
  EmailEntityType peopleType(AutomationRecordType record) => switch (this) {
        createTask || sendEmail => record.emailType,
        createPromise => EmailEntityType.promise,
        createDispute => EmailEntityType.dispute,
      };

  /// What the picked people are called in this action's own words.
  String get peopleLabel => this == sendEmail ? 'To *' : 'Assign to';

  /// A dispute is about one invoice or one payment; there is nothing on a customer to dispute, so
  /// the server refuses the pairing when the rule is written (`AutomationActions.disputeTarget`).
  /// Refused here too, where the author can still change their mind.
  bool allowedOn(AutomationRecordType record) =>
      this != createDispute || record != AutomationRecordType.customer;

  static AutomationAction? fromWire(String? wire) {
    for (final a in values) {
      if (a.wire == wire) return a;
    }
    return null;
  }
}

/// The status a rule's task opens at. Only the two a rule could sensibly raise: a rule that
/// created work already finished or already cancelled would be a rule that does nothing.
enum AutomationTaskStatus {
  open,
  inProgress;

  String get wire => this == open ? 'OPEN' : 'IN_PROGRESS';
  String get label => this == open ? 'Open' : 'In progress';

  static AutomationTaskStatus? fromWire(String? wire) {
    for (final s in values) {
      if (s.wire == wire) return s;
    }
    return null;
  }
}

/// The THEN's own parameters (`AutomationDtos.ActionSpec`). One record answers for all four
/// actions: what a rule needs to say is a line of text, a body, a date offset, an amount and who
/// it is for, and which of those matter is the action's business — checked by the server when the
/// rule is written, so a rule that cannot act is refused at authoring rather than skipped on a run
/// nobody is watching (R9).
@immutable
class AutomationActionSpec {
  /// The task's title or the email's subject.
  final String? title;

  /// The email's body, the task's notes, the promise's notes, or the dispute's reason.
  final String? body;

  /// Days from the run to the task's due date or the promise's promised date.
  final int? dueInDays;

  /// The promised amount. Never read off the record: a rule that guessed it from an invoice's
  /// balance would commit a customer to a number no person ever agreed.
  final double? amount;

  /// The email's From; null means the record's own sender role.
  final EmailToken? from;

  /// Who the action is for, picked exactly as everywhere else — the task's, promise's or
  /// dispute's assignees, or the email's To. One field, because it is one picker.
  final List<EmailToken> assignees;

  /// The task's opening status; null means whatever a task opens as by default.
  final String? status;

  const AutomationActionSpec({
    this.title,
    this.body,
    this.dueInDays,
    this.amount,
    this.from,
    this.assignees = const [],
    this.status,
  });

  factory AutomationActionSpec.fromJson(Map<String, dynamic> json) => AutomationActionSpec(
        title: json['title'] as String?,
        body: json['body'] as String?,
        dueInDays: (json['dueInDays'] as num?)?.toInt(),
        amount: (json['amount'] as num?)?.toDouble() ??
            double.tryParse('${json['amount'] ?? ''}'),
        from: json['from'] == null
            ? null
            : EmailToken.fromJson((json['from'] as Map).cast<String, dynamic>()),
        assignees: ((json['assignees'] as List?) ?? const [])
            .cast<Map>()
            .map((m) => EmailToken.fromJson(m.cast<String, dynamic>()))
            .toList(),
        status: json['status'] as String?,
      );
}

/// One rule as it reads back (`AutomationDtos.RuleDto`).
@immutable
class AutomationRule {
  final int id;
  final String name;
  final String? description;
  final bool enabled;

  /// Null when the server watches a kind of record this build does not know about.
  final AutomationRecordType? entityType;

  /// What the server called it, so an unknown kind still has something to show.
  final String entityTypeWire;

  final AutomationTrigger? trigger;
  final String triggerWire;

  /// The server's own words for the WHEN — "is created", "every day" — so one service decides how
  /// a rule reads rather than each screen wording it again.
  final String triggerLabel;

  /// The WHERE, as the `field:op:value` chips a list page puts on its query string.
  final List<String> filters;

  final AutomationAction? action;
  final String actionWire;

  /// The server's own words for the THEN: "create a task", "send an email".
  final String actionLabel;

  final AutomationActionSpec actionSpec;
  final int? createdByUserId;
  final DateTime? lastRunAt;
  final int runCount;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  const AutomationRule({
    required this.id,
    required this.name,
    this.description,
    this.enabled = true,
    this.entityType,
    this.entityTypeWire = '',
    this.trigger,
    this.triggerWire = '',
    this.triggerLabel = '',
    this.filters = const [],
    this.action,
    this.actionWire = '',
    this.actionLabel = '',
    this.actionSpec = const AutomationActionSpec(),
    this.createdByUserId,
    this.lastRunAt,
    this.runCount = 0,
    this.createdAt,
    this.updatedAt,
  });

  factory AutomationRule.fromJson(Map<String, dynamic> json) {
    final entityType = json['entityType'] as String? ?? '';
    final trigger = json['trigger'] as String? ?? '';
    final action = json['action'] as String? ?? '';
    return AutomationRule(
      id: (json['id'] as num).toInt(),
      name: json['name'] as String? ?? '',
      description: json['description'] as String?,
      enabled: json['enabled'] as bool? ?? true,
      entityType: AutomationRecordType.fromWire(entityType),
      entityTypeWire: entityType,
      trigger: AutomationTrigger.fromWire(trigger),
      triggerWire: trigger,
      triggerLabel: json['triggerLabel'] as String? ?? humanizeEnum(trigger),
      filters: ((json['filters'] as List?) ?? const []).map((f) => '$f').toList(),
      action: AutomationAction.fromWire(action),
      actionWire: action,
      actionLabel: json['actionLabel'] as String? ?? humanizeEnum(action),
      actionSpec: json['actionSpec'] == null
          ? const AutomationActionSpec()
          : AutomationActionSpec.fromJson((json['actionSpec'] as Map).cast<String, dynamic>()),
      createdByUserId: (json['createdByUserId'] as num?)?.toInt(),
      lastRunAt: json['lastRunAt'] == null ? null : DateTime.tryParse('${json['lastRunAt']}'),
      runCount: (json['runCount'] as num?)?.toInt() ?? 0,
      createdAt: json['createdAt'] == null ? null : DateTime.tryParse('${json['createdAt']}'),
      updatedAt: json['updatedAt'] == null ? null : DateTime.tryParse('${json['updatedAt']}'),
    );
  }

  /// What kind of record this watches, in a word.
  String get recordLabel => entityType?.label ?? humanizeEnum(entityTypeWire);

  /// The WHERE as chips the list page's own filter editor understands. One the app cannot read is
  /// left out here but kept in [filters], so nothing is dropped behind the author's back —
  /// [unreadableFilters] is what the editor warns about.
  List<TableFilter> get filterChips =>
      filters.map(TableFilter.parse).whereType<TableFilter>().toList();

  /// The filters that did not parse. Always empty against this server, which writes them itself;
  /// it exists so that if one ever is not, the editor says so instead of silently rewriting the
  /// rule without it.
  List<String> get unreadableFilters =>
      [for (final f in filters) if (TableFilter.parse(f) == null) f];

  /// The whole rule in one line, for a row's tooltip: "When an invoice is updated, where Status is
  /// UNPAID, create a task". [schema] names the filter columns; without it they read as their
  /// field names, which is still better than nothing.
  String describe(TableSchema? schema) {
    final where = filterChips.map((f) => describeFilter(f, schema)).join(', ');
    return [
      'When ${entityType?.nounWithArticle ?? recordLabel.toLowerCase()} $triggerLabel',
      if (where.isNotEmpty) 'where $where',
      actionLabel,
    ].join(', ');
  }
}

/// What "Run now" did (`AutomationDtos.RunResultDto`): how many records the rule matches, and how
/// many pieces of work that actually queued.
@immutable
class AutomationRunResult {
  final int queued;
  final int matched;

  /// The ceiling one run stops at.
  final int cap;
  final bool capped;

  const AutomationRunResult({
    this.queued = 0,
    this.matched = 0,
    this.cap = 0,
    this.capped = false,
  });

  factory AutomationRunResult.fromJson(Map<String, dynamic> json) => AutomationRunResult(
        queued: (json['queued'] as num?)?.toInt() ?? 0,
        matched: (json['matched'] as num?)?.toInt() ?? 0,
        cap: (json['cap'] as num?)?.toInt() ?? 0,
        capped: json['capped'] as bool? ?? false,
      );

  /// The snackbar after Run now. Matching nothing and queueing nothing are different answers and
  /// are worded differently: the second means the rule has already been run over these records
  /// and will not do the same work twice (R4), which reads as a failure if it is not said.
  String get message {
    final records = '$matched record${matched == 1 ? '' : 's'}';
    if (matched == 0) return 'Nothing matches this rule right now';
    if (queued == 0) return 'All $records already had this done';
    final ran = 'Queued $queued of $records';
    return capped
        ? '$ran — that is all one run does at once; narrow the filters and run it again for the rest'
        : ran;
  }
}

/// What became of one thing a rule was asked to do (`AutomationEventStatus`).
///
/// SKIPPED is the ordinary answer, not a fault: the record stopped matching between the trigger
/// and the run, or the rule had already done this for it and does not do it twice (R4). It is
/// still the answer somebody looking for why a rule is doing nothing has come for, which is why
/// it is shown as plainly as a failure.
enum AutomationRunStatus {
  queued,
  running,
  done,
  skipped,
  failed;

  String get wire => name.toUpperCase();

  String get label => switch (this) {
        queued => 'Queued',
        running => 'Running',
        done => 'Done',
        skipped => 'Skipped',
        failed => 'Failed',
      };

  static AutomationRunStatus? fromWire(String? wire) {
    for (final s in values) {
      if (s.wire == wire) return s;
    }
    return null;
  }
}

/// One record a rule was asked about, and what came of it (`AutomationDtos.RuleRunDto`).
///
/// This is the only place a rule that is failing or skipping every time says so: the row on the
/// list shows `lastRunAt` and `runCount`, and the worker moves both only when an action actually
/// happened, so a rule that has skipped a thousand records reads there exactly like one that has
/// never matched anything.
@immutable
class AutomationRuleRun {
  final int id;
  final int? ruleId;

  /// The kind of record it was asked about; null when the server watches a kind this build does
  /// not know, which [recordLabel] still names from [entityTypeWire].
  final AutomationRecordType? entityType;
  final String entityTypeWire;
  final int? entityId;

  /// What set it off, as the server spells it. The rule's own trigger, except for a run somebody
  /// started by hand.
  final String triggerWire;

  final AutomationRunStatus? status;

  /// What the server called it, so a status this build has not heard of still reads as something.
  final String statusWire;

  /// How many times the worker has taken this one on. More than one means it failed and was
  /// picked up again.
  final int attempts;

  /// Why it skipped or failed; null while it is still going, and when it simply worked.
  final String? lastError;

  final DateTime? enqueuedAt;
  final DateTime? nextAttemptAt;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  const AutomationRuleRun({
    required this.id,
    this.ruleId,
    this.entityType,
    this.entityTypeWire = '',
    this.entityId,
    this.triggerWire = '',
    this.status,
    this.statusWire = '',
    this.attempts = 0,
    this.lastError,
    this.enqueuedAt,
    this.nextAttemptAt,
    this.createdAt,
    this.updatedAt,
  });

  factory AutomationRuleRun.fromJson(Map<String, dynamic> json) {
    final entityType = json['entityType'] as String? ?? '';
    final status = json['status'] as String? ?? '';
    return AutomationRuleRun(
      id: (json['id'] as num).toInt(),
      ruleId: (json['ruleId'] as num?)?.toInt(),
      entityType: AutomationRecordType.fromWire(entityType),
      entityTypeWire: entityType,
      entityId: (json['entityId'] as num?)?.toInt(),
      triggerWire: json['trigger'] as String? ?? '',
      status: AutomationRunStatus.fromWire(status),
      statusWire: status,
      attempts: (json['attempts'] as num?)?.toInt() ?? 0,
      lastError: json['lastError'] as String?,
      enqueuedAt: json['enqueuedAt'] == null ? null : DateTime.tryParse('${json['enqueuedAt']}'),
      nextAttemptAt:
          json['nextAttemptAt'] == null ? null : DateTime.tryParse('${json['nextAttemptAt']}'),
      createdAt: json['createdAt'] == null ? null : DateTime.tryParse('${json['createdAt']}'),
      updatedAt: json['updatedAt'] == null ? null : DateTime.tryParse('${json['updatedAt']}'),
    );
  }

  /// The status in whatever words there are for it — the app's, or the server's own for one this
  /// build has not heard of.
  String get statusLabel => status?.label ?? humanizeEnum(statusWire);

  /// Which record it was about: "Invoice #42". Without an id it is the kind alone — a sweep that
  /// never reached a record.
  String get recordLabel {
    final kind = entityType?.label ?? humanizeEnum(entityTypeWire);
    return entityId == null ? kind : '$kind #$entityId';
  }

  /// When it last moved: what the runs list orders and dates its rows by.
  DateTime? get when => updatedAt ?? createdAt ?? enqueuedAt;

  /// Still on its way: the worker has not finished with it, so [lastError] is not the last word.
  bool get inProgress =>
      status == AutomationRunStatus.queued || status == AutomationRunStatus.running;
}
