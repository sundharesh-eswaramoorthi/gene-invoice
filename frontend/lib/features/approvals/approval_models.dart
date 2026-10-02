/// Where a held change stands (B2). The wire names are the server's; an unknown one reads as
/// PENDING, like the other status parsers (G25).
enum PendingStatus { PENDING, APPROVED, REJECTED, WITHDRAWN, SUPERSEDED, FAILED, CLOSED }

PendingStatus parsePendingStatus(Object? raw) =>
    PendingStatus.values.asNameMap()[raw] ?? PendingStatus.PENDING;

PendingStatus? parsePendingStatusOrNull(Object? raw) =>
    raw == null ? null : parsePendingStatus(raw);

String pendingStatusLabel(PendingStatus s) => switch (s) {
      PendingStatus.PENDING => 'Awaiting approval',
      PendingStatus.APPROVED => 'Approved',
      PendingStatus.REJECTED => 'Rejected',
      PendingStatus.WITHDRAWN => 'Withdrawn',
      PendingStatus.SUPERSEDED => 'Replaced',
      PendingStatus.FAILED => 'Failed',
      PendingStatus.CLOSED => 'Closed',
    };

/// Someone named on a change: its maker, or whoever decided it. Null on a customer login's view.
class ApprovalPerson {
  final int? id;
  final String name;
  const ApprovalPerson({this.id, required this.name});

  static ApprovalPerson? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = (raw['id'] as num?)?.toInt();
    final name = raw['name'] as String? ?? raw['username'] as String?;
    if (name == null && id == null) return null;
    return ApprovalPerson(id: id, name: name ?? 'User #$id');
  }
}

double? _money(Object? raw) => raw is num ? raw.toDouble() : double.tryParse('${raw ?? ''}');

DateTime? _instant(Object? raw) => raw is String ? DateTime.tryParse(raw) : null;

/// Where a record of [entityType] opens in the app, or null for one with no page of its own.
String? approvalEntityRoute(String? entityType, int? id) {
  if (id == null) return null;
  return switch (entityType) {
    'INVOICE' => '/invoices/$id',
    'PAYMENT' => '/payments/$id',
    'PROMISE' => '/promises/$id',
    'DISPUTE' => '/disputes/$id',
    'CUSTOMER' => '/customers/$id',
    // A region's change is to its approval limits (THRESHOLD_CHANGE); they open where anyone
    // who approves or manages them can reach, which a region's own page is not (REGION_VIEW).
    'REGION' => '/approval-thresholds?regionId=$id',
    _ => null,
  };
}

/// One row of the approvals list, the record banner and a 202's `pendingChange` (§6.11 Brief).
/// Every field but the id is optional: a customer login sees fewer of them (§5.11.9), and a
/// threshold change (entity type REGION) has no customer.
class PendingChangeBrief {
  final int id;
  final PendingStatus status;

  /// The status on the date viewed; only set while a past date is viewed (B3).
  final PendingStatus? statusAt;
  final String? operation;
  final String? operationLabel;
  final String? entityType;

  /// Null for a create that is still waiting: the record does not exist yet.
  final int? entityId;
  final String? entityLabel;
  final int? customerId;
  final String? customerName;
  final int? regionId;
  final String? regionName;
  final double? amount;
  final double? threshold;
  final String summary;
  final DateTime? requestedAt;
  final ApprovalPerson? requestedBy;
  final String? origin;
  final DateTime? decidedAt;

  const PendingChangeBrief({
    required this.id,
    this.status = PendingStatus.PENDING,
    this.statusAt,
    this.operation,
    this.operationLabel,
    this.entityType,
    this.entityId,
    this.entityLabel,
    this.customerId,
    this.customerName,
    this.regionId,
    this.regionName,
    this.amount,
    this.threshold,
    this.summary = '',
    this.requestedAt,
    this.requestedBy,
    this.origin,
    this.decidedAt,
  });

  factory PendingChangeBrief.fromJson(Map<String, dynamic> json) => PendingChangeBrief(
        id: (json['id'] as num).toInt(),
        status: parsePendingStatus(json['status']),
        statusAt: parsePendingStatusOrNull(json['statusAt']),
        operation: json['operation'] as String?,
        operationLabel: json['operationLabel'] as String?,
        entityType: json['entityType'] as String?,
        entityId: (json['entityId'] as num?)?.toInt(),
        entityLabel: json['entityLabel'] as String?,
        customerId: (json['customerId'] as num?)?.toInt(),
        customerName: json['customerName'] as String?,
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        amount: _money(json['amount']),
        threshold: _money(json['threshold']),
        summary: json['summary'] as String? ?? '',
        requestedAt: _instant(json['requestedAt']),
        requestedBy: ApprovalPerson.fromJson(json['requestedBy']),
        origin: json['origin'] as String?,
        decidedAt: _instant(json['decidedAt']),
      );

  /// What the change is to, as a reader would name it: "INV-…", "South", "New invoice for Acme".
  String get recordText {
    if (entityLabel != null && entityLabel!.isNotEmpty) return entityLabel!;
    if (entityType == 'REGION') return regionName ?? 'Region #$entityId';
    if (entityId == null) {
      final what = operationLabel ?? 'New record';
      return customerName == null ? what : '$what for $customerName';
    }
    return '${entityType ?? 'Record'} #$entityId';
  }
}

/// One field a change would alter, as the detail's table lists it.
class FieldChange {
  final String field;
  final String label;
  final Object? current;
  final Object? proposed;

  /// The values as a person reads them ('Sam South' for a POC's user id), when the server says;
  /// null otherwise, and the page formats the raw value itself.
  final String? currentText;
  final String? proposedText;

  /// The record has moved on since the request: what "current" shows is not what the maker saw.
  final bool changedSinceRequest;

  const FieldChange({
    required this.field,
    required this.label,
    this.current,
    this.proposed,
    this.currentText,
    this.proposedText,
    this.changedSinceRequest = false,
  });

  factory FieldChange.fromJson(Map<String, dynamic> json) {
    final field = json['field'] as String? ?? '';
    return FieldChange(
      field: field,
      label: json['label'] as String? ?? field,
      current: json['current'],
      proposed: json['proposed'],
      currentText: json['currentText'] as String?,
      proposedText: json['proposedText'] as String?,
      changedSinceRequest: json['changedSinceRequest'] as bool? ?? false,
    );
  }
}

/// What an approved change produced, and where it opens.
class ApprovalResult {
  final String? entityType;
  final int? entityId;
  final String? link;
  const ApprovalResult({this.entityType, this.entityId, this.link});

  static ApprovalResult? fromJson(Object? raw) {
    if (raw is! Map) return null;
    return ApprovalResult(
      entityType: raw['entityType'] as String?,
      entityId: (raw['entityId'] as num?)?.toInt(),
      link: raw['link'] as String?,
    );
  }

  String? get route => link ?? approvalEntityRoute(entityType, entityId);
}

/// The server's `blockedReason` when the caller cannot open the record the change is about
/// (§5.11.9, SC-15): the change stays visible, but its record does not.
const String kNoRecordAccessReason = 'You no longer have access to this record';

/// A change in full (§6.11 Detail). What the caller may do with it comes from the server's flags
/// alone (D14); they are all false while a past date is viewed.
class PendingChangeDetail extends PendingChangeBrief {
  final String? affectedType;
  final int? affectedId;
  final String? measureLabel;
  final List<FieldChange> changes;

  /// The record as it is now and as the change would leave it; null when the caller can no longer
  /// see the record (SC-15).
  final Map<String, dynamic>? current;
  final Map<String, dynamic>? proposed;
  final int? ruleId;
  final ApprovalPerson? decidedBy;
  final String? decisionNote;
  final String? failureReason;
  final int? supersededById;
  final ApprovalResult? result;
  final int? approverCount;
  final bool canApprove;
  final bool canReject;
  final bool canWithdraw;

  /// Why the caller cannot decide it, when they cannot: "You requested this change", say.
  final String? blockedReason;

  const PendingChangeDetail({
    required super.id,
    super.status,
    super.statusAt,
    super.operation,
    super.operationLabel,
    super.entityType,
    super.entityId,
    super.entityLabel,
    super.customerId,
    super.customerName,
    super.regionId,
    super.regionName,
    super.amount,
    super.threshold,
    super.summary,
    super.requestedAt,
    super.requestedBy,
    super.origin,
    super.decidedAt,
    this.affectedType,
    this.affectedId,
    this.measureLabel,
    this.changes = const [],
    this.current,
    this.proposed,
    this.ruleId,
    this.decidedBy,
    this.decisionNote,
    this.failureReason,
    this.supersededById,
    this.result,
    this.approverCount,
    this.canApprove = false,
    this.canReject = false,
    this.canWithdraw = false,
    this.blockedReason,
  });

  factory PendingChangeDetail.fromJson(Map<String, dynamic> json) {
    final b = PendingChangeBrief.fromJson(json);
    Map<String, dynamic>? map(Object? raw) => raw is Map ? raw.cast<String, dynamic>() : null;
    return PendingChangeDetail(
      id: b.id,
      status: b.status,
      statusAt: b.statusAt,
      operation: b.operation,
      operationLabel: b.operationLabel,
      entityType: b.entityType,
      entityId: b.entityId,
      entityLabel: b.entityLabel,
      customerId: b.customerId,
      customerName: b.customerName,
      regionId: b.regionId,
      regionName: b.regionName,
      amount: b.amount,
      threshold: b.threshold,
      summary: b.summary,
      requestedAt: b.requestedAt,
      requestedBy: b.requestedBy,
      origin: b.origin,
      decidedAt: b.decidedAt,
      affectedType: json['affectedType'] as String?,
      affectedId: (json['affectedId'] as num?)?.toInt(),
      measureLabel: json['measureLabel'] as String?,
      changes: (json['changes'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => FieldChange.fromJson(m.cast<String, dynamic>()))
          .toList(),
      current: map(json['current']),
      proposed: map(json['proposed']),
      ruleId: (json['ruleId'] as num?)?.toInt(),
      decidedBy: ApprovalPerson.fromJson(json['decidedBy']),
      decisionNote: json['decisionNote'] as String?,
      failureReason: json['failureReason'] as String?,
      supersededById: (json['supersededById'] as num?)?.toInt(),
      result: ApprovalResult.fromJson(json['result']),
      approverCount: (json['approverCount'] as num?)?.toInt(),
      canApprove: json['canApprove'] as bool? ?? false,
      canReject: json['canReject'] as bool? ?? false,
      canWithdraw: json['canWithdraw'] as bool? ?? false,
      blockedReason: json['blockedReason'] as String?,
    );
  }

  /// The caller cannot open the record the change is about (SC-15), so the page must not link
  /// into it or its customer.
  bool get recordHidden => blockedReason == kNoRecordAccessReason;

  /// Where the record the change is about opens: the one it produced once approved, otherwise
  /// the record it touches.
  String? get recordRoute =>
      result?.route ??
      approvalEntityRoute(entityType, entityId) ??
      approvalEntityRoute(affectedType, affectedId);
}

/// One amount-gated operation's limit in a region (§6.11). No amount means it is not gated.
class ThresholdRow {
  final String operation;
  final String label;
  final String? measure;
  final double? amount;

  /// How many hours of the same person's changes to one customer count together; 0 is each
  /// change alone (§5.11.3).
  final int? windowHours;
  final DateTime? updatedAt;
  final ApprovalPerson? updatedBy;

  const ThresholdRow({
    required this.operation,
    required this.label,
    this.measure,
    this.amount,
    this.windowHours,
    this.updatedAt,
    this.updatedBy,
  });

  factory ThresholdRow.fromJson(Map<String, dynamic> json) {
    final op = json['operation'] as String? ?? '';
    return ThresholdRow(
      operation: op,
      label: json['label'] as String? ?? op,
      measure: json['measure'] as String?,
      amount: _money(json['amount']),
      windowHours: (json['windowHours'] as num?)?.toInt(),
      updatedAt: _instant(json['updatedAt']),
      updatedBy: ApprovalPerson.fromJson(json['updatedBy']),
    );
  }
}

/// A region's approval limits, as the thresholds GET and PUT both answer.
class RegionThresholds {
  final int regionId;
  final String? regionName;
  final bool canEdit;
  final int? activeApprovers;

  /// The region's outstanding threshold change, if a loosening is waiting for a second approver.
  final PendingChangeBrief? pendingChange;
  final List<ThresholdRow> thresholds;
  final List<String> warnings;

  const RegionThresholds({
    required this.regionId,
    this.regionName,
    this.canEdit = false,
    this.activeApprovers,
    this.pendingChange,
    this.thresholds = const [],
    this.warnings = const [],
  });

  factory RegionThresholds.fromJson(Map<String, dynamic> json) {
    final pending = json['pendingChange'];
    return RegionThresholds(
      regionId: (json['regionId'] as num).toInt(),
      regionName: json['regionName'] as String?,
      canEdit: json['canEdit'] as bool? ?? false,
      activeApprovers: (json['activeApprovers'] as num?)?.toInt(),
      // A change with no id could not be opened; it is left out rather than failing the whole
      // answer, which a PUT that applied its tightening rows still has to show.
      pendingChange: pending is Map && pending['id'] is num
          ? PendingChangeBrief.fromJson(pending.cast<String, dynamic>())
          : null,
      thresholds: (json['thresholds'] as List? ?? const [])
          .whereType<Map>()
          .map((m) => ThresholdRow.fromJson(m.cast<String, dynamic>()))
          .toList(),
      warnings: (json['warnings'] as List? ?? const []).map((w) => '$w').toList(),
    );
  }
}
