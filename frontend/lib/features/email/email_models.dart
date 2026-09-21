import 'package:flutter/foundation.dart';

import '../../core/format.dart';
import 'email_entity.dart';

/// Why the compose dialog opened after a save: it pre-fills a suggestion for that event (E12).
enum EmailEvent {
  created,
  updated;

  String get wire => name.toUpperCase();
}

List<Map<String, dynamic>> _maps(Object? raw) =>
    ((raw as List?) ?? const []).cast<Map>().map((m) => m.cast<String, dynamic>()).toList();

Map<String, dynamic>? _map(Object? raw) => raw is Map ? raw.cast<String, dynamic>() : null;

DateTime? _date(Object? raw) => raw == null ? null : DateTime.tryParse(raw.toString());

/// Which people a role token means (L1): everyone holding that seat on the record's customer, or
/// the one person the record itself stores.
abstract final class EmailRoleLevel {
  static const customer = 'CUSTOMER';
  static const record = 'RECORD';
}

@immutable
class EmailToken {
  final String type;
  final int? userId;

  final String? role;

  /// An [EmailRoleLevel] for a role. Null on a token written before levels, which the server reads
  /// as customer level where the type offers one (L7).
  final String? level;

  const EmailToken.user(int id)
      : type = 'USER',
        userId = id,
        role = null,
        level = null;

  const EmailToken.role(String key, {this.level})
      : type = 'ROLE',
        userId = null,
        role = key;

  const EmailToken.customer()
      : type = 'CUSTOMER',
        userId = null,
        role = null,
        level = null;

  const EmailToken._(this.type, this.userId, this.role, this.level);

  factory EmailToken.fromJson(Map<String, dynamic> json) => EmailToken._(
        json['type'] as String,
        (json['userId'] as num?)?.toInt(),
        json['role'] as String?,
        json['level'] as String?,
      );

  bool get isUser => type == 'USER';
  bool get isRole => type == 'ROLE';
  bool get isCustomer => type == 'CUSTOMER';

  Map<String, dynamic> toJson() => {
        'type': type,
        if (userId != null) 'userId': userId,
        if (role != null) 'role': role,
        if (level != null) 'level': level,
      };

  @override
  bool operator ==(Object other) =>
      other is EmailToken &&
      other.type == type &&
      other.userId == userId &&
      other.role == role &&
      other.level == level;

  @override
  int get hashCode => Object.hash(type, userId, role, level);

  @override
  String toString() =>
      'EmailToken($type${userId ?? ''}${role ?? ''}${level == null ? '' : ':$level'})';
}

@immutable
class EmailSource {
  final String type;
  final String? role;

  /// An [EmailRoleLevel] on a ROLE source (L1); null on any other, and on a role written before
  /// levels, which kept none — the server names it by the role alone rather than claiming one (L7).
  final String? level;

  final String? label;

  const EmailSource({required this.type, this.role, this.level, this.label});

  factory EmailSource.fromJson(Map<String, dynamic> json) => EmailSource(
        type: json['type'] as String? ?? '',
        role: json['role'] as String?,
        level: json['level'] as String?,
        label: json['label'] as String?,
      );

  String get description => switch (type) {
        'USER' => 'added directly',
        'ROLE' => label ?? humanizeEnum(role),
        'CUSTOMER' => 'customer email',
        // A reply to the shared mailbox counts as addressed to the person who wrote to them (E8).
        'MAILBOX' => 'mailbox',
        'HEADER' => 'as addressed',
        _ => humanizeEnum(type).toLowerCase(),
      };
}

@immutable
class RecipientDelivery {
  final String? status;

  final String? error;
  final DateTime? sentAt;
  final DateTime? deliveredAt;
  final bool deliveredConfirmed;

  final DateTime? readAt;
  final DateTime? bouncedAt;

  final DateTime? readInAppAt;

  const RecipientDelivery({
    this.status,
    this.error,
    this.sentAt,
    this.deliveredAt,
    this.deliveredConfirmed = false,
    this.readAt,
    this.bouncedAt,
    this.readInAppAt,
  });

  factory RecipientDelivery.fromJson(Map<String, dynamic> json) => RecipientDelivery(
        status: json['status'] as String?,
        error: json['error'] as String?,
        sentAt: _date(json['sentAt']),
        deliveredAt: _date(json['deliveredAt']),
        deliveredConfirmed: json['deliveredConfirmed'] as bool? ?? false,
        readAt: _date(json['readAt']),
        bouncedAt: _date(json['bouncedAt']),
        readInAppAt: _date(json['readInAppAt']),
      );

  bool get inProgress => status == 'QUEUED' || status == 'SENDING';

  bool get tracking => status == 'SENT' || status == 'DELIVERED';
}

String? recipientDeliveryText(RecipientDelivery delivery) {
  String at(String word, DateTime? time) =>
      time == null ? word : '$word ${formatDateTime(time)}';
  String why(String word) {
    final error = delivery.error;
    return error == null || error.isEmpty ? word : '$word: $error';
  }

  return switch (delivery.status) {
    null => null,
    'QUEUED' => 'Queued',
    'SENDING' => 'Sending',
    'SENT' => at('Sent', delivery.sentAt),
    'DELIVERED' => delivery.deliveredConfirmed
        ? at('Delivered', delivery.deliveredAt)
        : '${at('Delivered', delivery.deliveredAt)} (estimated)',
    'READ' => at('Read', delivery.readAt),
    'BOUNCED' => why('Bounced'),
    'FAILED' => why('Failed'),
    'NOT_SENT' => why('Not sent'),
    final other => humanizeEnum(other),
  };
}

/// A sender or recipient as this viewer may see them. Masked people — staff, to a customer
/// login — arrive as their role label or "Gene Invoice team", with no address (E13).
@immutable
class EmailParticipant {
  final String name;
  final String? address;
  final int? userId;
  final bool internal;
  final bool masked;
  final List<EmailSource> sources;

  final RecipientDelivery? delivery;

  const EmailParticipant({
    required this.name,
    this.address,
    this.userId,
    this.internal = false,
    this.masked = false,
    this.sources = const [],
    this.delivery,
  });

  factory EmailParticipant.fromJson(Map<String, dynamic> json) => EmailParticipant(
        name: json['name'] as String? ?? '',
        address: json['address'] as String?,
        userId: (json['userId'] as num?)?.toInt(),
        internal: json['internal'] as bool? ?? false,
        masked: json['masked'] as bool? ?? false,
        sources: _maps(json['sources']).map(EmailSource.fromJson).toList(),
        delivery: _map(json['delivery']) == null
            ? null
            : RecipientDelivery.fromJson(_map(json['delivery'])!),
      );

  String get display {
    if (address == null || address!.isEmpty) return name.isEmpty ? '—' : name;
    if (name.isEmpty || name == address) return address!;
    return '$name <$address>';
  }

  String get howAdded => sources
      .map((s) => s.description)
      .where((d) => d != name)
      .toSet()
      .join(', ');
}

@immutable
class EmailRoleOption {
  final String role;
  final String label;

  /// An [EmailRoleLevel]: the customer's POC book, or what this record stores (L1).
  final String level;

  final String levelLabel;

  final String groupLabel;

  final bool? resolved;

  final List<EmailPerson> people;

  final EmailPerson? sender;

  const EmailRoleOption({
    required this.role,
    required this.label,
    this.level = EmailRoleLevel.customer,
    this.levelLabel = 'Customer',
    this.groupLabel = 'Customer level',
    this.resolved,
    this.people = const [],
    this.sender,
  });

  factory EmailRoleOption.fromJson(Map<String, dynamic> json) {
    // A role sent before levels is the customer's POC book (L7).
    final level = json['level'] as String? ?? EmailRoleLevel.customer;
    final levelLabel =
        json['levelLabel'] as String? ?? (level == EmailRoleLevel.record ? 'Record' : 'Customer');
    return EmailRoleOption(
      role: json['role'] as String,
      label: json['label'] as String? ?? humanizeEnum(json['role'] as String?),
      level: level,
      levelLabel: levelLabel,
      groupLabel: json['groupLabel'] as String? ?? '$levelLabel level',
      resolved: json['resolved'] as bool?,
      people: _maps(json['people']).map(EmailPerson.fromJson).toList(),
      sender: _map(json['sender']) == null ? null : EmailPerson.fromJson(_map(json['sender'])!),
    );
  }

  EmailToken get token => EmailToken.role(role, level: level);

  String get labelWithLevel => level == EmailRoleLevel.record
      ? '$label (this ${levelLabel.toLowerCase()})'
      : '$label (customer)';
}

@immutable
class EmailRoleGroup {
  final String level;

  final String label;
  final List<EmailRoleOption> roles;

  const EmailRoleGroup({required this.level, required this.label, required this.roles});
}

@immutable
class EmailPerson {
  final int? userId;
  final String name;
  final String? username;
  final String? email;

  final String? gmail;

  const EmailPerson({this.userId, required this.name, this.username, this.email, this.gmail});

  factory EmailPerson.fromJson(Map<String, dynamic> json) => EmailPerson(
        userId: (json['userId'] as num?)?.toInt(),
        name: json['name'] as String? ?? json['username'] as String? ?? '',
        username: json['username'] as String?,
        email: json['email'] as String?,
        gmail: json['gmail'] as String?,
      );

  String get display => email == null || email!.isEmpty ? name : '$name <$email>';

  bool get gmailNotConnected => gmail != null && gmail != GmailStatus.connected;
}

abstract final class GmailStatus {
  static const connected = 'CONNECTED';
  static const needsReconnect = 'NEEDS_RECONNECT';
  static const notConnected = 'NOT_CONNECTED';
}

@immutable
class GmailConnection {
  final bool configured;

  final String status;
  final String? gmailAddress;

  final String? clientId;

  final String? reason;
  final DateTime? connectedAt;

  final DateTime? lastSyncedAt;
  final String? lastSyncError;

  final String? serviceError;
  final DateTime? updatedAt;

  const GmailConnection({
    this.configured = true,
    this.status = GmailStatus.notConnected,
    this.gmailAddress,
    this.clientId,
    this.reason,
    this.connectedAt,
    this.lastSyncedAt,
    this.lastSyncError,
    this.serviceError,
    this.updatedAt,
  });

  factory GmailConnection.fromJson(Map<String, dynamic> json) => GmailConnection(
        configured: json['configured'] as bool? ?? true,
        status: json['status'] as String? ?? GmailStatus.notConnected,
        gmailAddress: json['gmailAddress'] as String?,
        clientId: json['clientId'] as String?,
        reason: json['reason'] as String?,
        connectedAt: _date(json['connectedAt']),
        lastSyncedAt: _date(json['lastSyncedAt']),
        lastSyncError: json['lastSyncError'] as String?,
        serviceError: json['serviceError'] as String?,
        updatedAt: _date(json['updatedAt']),
      );

  bool get isConnected => status == GmailStatus.connected;
  bool get needsReconnect => status == GmailStatus.needsReconnect;

  bool get exists => isConnected || needsReconnect;
}

@immutable
class EmailAddress {
  final String name;
  final String address;

  const EmailAddress({required this.name, required this.address});

  factory EmailAddress.fromJson(Map<String, dynamic> json) => EmailAddress(
        name: json['name'] as String? ?? '',
        address: json['address'] as String? ?? '',
      );
}

@immutable
class EmailDelivery {
  final bool configured;

  final GmailConnection? gmail;

  const EmailDelivery({required this.configured, this.gmail});

  factory EmailDelivery.fromJson(Map<String, dynamic> json) => EmailDelivery(
        configured: json['configured'] as bool? ?? false,
        gmail: _map(json['gmail']) == null ? null : GmailConnection.fromJson(_map(json['gmail'])!),
      );
}

@immutable
class EmailSuggestion {
  final String subject;
  final String body;
  final List<EmailToken> to;

  const EmailSuggestion({required this.subject, required this.body, required this.to});

  factory EmailSuggestion.fromJson(Map<String, dynamic> json) => EmailSuggestion(
        subject: json['subject'] as String? ?? '',
        body: json['body'] as String? ?? '',
        to: _maps(json['to']).map(EmailToken.fromJson).toList(),
      );
}

@immutable
class EmailContext {
  final EmailEntityType? entityType;
  final int? entityId;
  final String? entityLabel;
  final String? entityLink;
  final EmailDelivery delivery;

  final bool restricted;
  final EmailPerson self;
  final List<EmailRoleOption> roles;
  final bool customerEmailsAvailable;
  final List<EmailAddress> customerAddresses;
  final EmailSuggestion? suggestion;

  const EmailContext({
    required this.entityType,
    required this.entityId,
    required this.entityLabel,
    required this.entityLink,
    required this.delivery,
    required this.restricted,
    required this.self,
    required this.roles,
    required this.customerEmailsAvailable,
    required this.customerAddresses,
    required this.suggestion,
  });

  factory EmailContext.fromJson(Map<String, dynamic> json) {
    final sender = _map(json['sender']) ?? const {};
    final customerEmails = _map(json['customerEmails']) ?? const {};
    return EmailContext(
      entityType: EmailEntityType.fromWire(json['entityType'] as String?),
      entityId: (json['entityId'] as num?)?.toInt(),
      entityLabel: json['entityLabel'] as String?,
      entityLink: json['entityLink'] as String?,
      delivery: EmailDelivery.fromJson(_map(json['delivery']) ?? const {}),
      restricted: sender['restricted'] as bool? ?? false,
      self: EmailPerson.fromJson(_map(sender['self']) ?? const {}),
      roles: _maps(json['roles']).map(EmailRoleOption.fromJson).toList(),
      customerEmailsAvailable: customerEmails['available'] as bool? ?? false,
      customerAddresses: _maps(customerEmails['addresses']).map(EmailAddress.fromJson).toList(),
      suggestion: _map(json['suggestion']) == null
          ? null
          : EmailSuggestion.fromJson(_map(json['suggestion'])!),
    );
  }

  /// The role offered at [level]; without one, the first offered under that key — the customer's
  /// where the type has one, as the server reads a token that carries no level (L7).
  EmailRoleOption? role(String key, {String? level}) {
    for (final r in roles) {
      if (r.role == key && (level == null || r.level == level)) return r;
    }
    return null;
  }

  EmailRoleOption? roleOf(EmailToken token) =>
      token.isRole ? role(token.role!, level: token.level) : null;

  List<EmailRoleGroup> get roleGroups {
    final byLevel = <String, List<EmailRoleOption>>{};
    for (final r in roles) {
      (byLevel[r.level] ??= []).add(r);
    }
    return [
      for (final entry in byLevel.entries)
        EmailRoleGroup(level: entry.key, label: entry.value.first.groupLabel, roles: entry.value),
    ];
  }
}

@immutable
class EmailUnresolved {
  final String token;
  final String label;
  final String? reason;

  const EmailUnresolved({required this.token, required this.label, this.reason});

  factory EmailUnresolved.fromJson(Map<String, dynamic> json) => EmailUnresolved(
        token: json['token'] as String? ?? '',
        label: json['label'] as String? ?? humanizeEnum(json['token'] as String?),
        reason: json['reason'] as String?,
      );
}

@immutable
class EmailPreview {
  final EmailParticipant? from;
  final List<EmailParticipant> to;
  final List<EmailUnresolved> unresolved;

  final List<String> problems;

  final List<String> warnings;

  const EmailPreview({
    required this.from,
    required this.to,
    required this.unresolved,
    required this.problems,
    this.warnings = const [],
  });

  factory EmailPreview.fromJson(Map<String, dynamic> json) => EmailPreview(
        from: _map(json['from']) == null ? null : EmailParticipant.fromJson(_map(json['from'])!),
        to: _maps(json['to']).map(EmailParticipant.fromJson).toList(),
        unresolved: _maps(json['unresolved']).map(EmailUnresolved.fromJson).toList(),
        problems: ((json['problems'] as List?) ?? const []).map((e) => '$e').toList(),
        warnings: ((json['warnings'] as List?) ?? const []).map((e) => '$e').toList(),
      );
}

const notSentFromCustomerLogin = 'Email from a customer login is not sent through Gmail';

@immutable
class EmailMessage {
  final int id;
  final EmailEntityType? entityType;
  final int entityId;
  final String entityLabel;
  final String? entityLink;

  final String direction;

  final String status;
  final String subject;
  final String body;
  final EmailParticipant from;
  final String? fromRole;
  final String? fromRoleLabel;
  final List<EmailParticipant> to;
  final List<EmailParticipant> cc;
  final List<EmailUnresolved> unresolved;

  final String? sentByName;
  final int? sentByUserId;
  final String? deliveredFrom;
  final String? error;
  final int attempts;
  final DateTime? occurredAt;
  final DateTime? sentAt;
  final bool canRetry;

  final bool canOpenRecord;

  final bool? readByMe;

  const EmailMessage({
    required this.id,
    required this.entityType,
    required this.entityId,
    required this.entityLabel,
    this.entityLink,
    required this.direction,
    required this.status,
    required this.subject,
    required this.body,
    required this.from,
    this.fromRole,
    this.fromRoleLabel,
    this.to = const [],
    this.cc = const [],
    this.unresolved = const [],
    this.sentByName,
    this.sentByUserId,
    this.deliveredFrom,
    this.error,
    this.attempts = 0,
    this.occurredAt,
    this.sentAt,
    this.canRetry = false,
    this.canOpenRecord = false,
    this.readByMe,
  });

  bool get isInbound => direction == 'INBOUND';

  /// Whether a retry could get this email out. The server says whether the email is in a state a
  /// retry acts on; an email a customer login wrote is not one of them — a customer login never
  /// connects Gmail (mail-service.md M13), so it comes back not sent for the same reason every
  /// time, and offering the button only promises something that can never happen (QMX-4).
  bool get canRetryNow => canRetry && !(error?.contains(notSentFromCustomerLogin) ?? false);

  factory EmailMessage.fromJson(Map<String, dynamic> json) {
    final sentBy = _map(json['sentBy']);
    return EmailMessage(
      id: (json['id'] as num).toInt(),
      entityType: EmailEntityType.fromWire(json['entityType'] as String?),
      entityId: (json['entityId'] as num?)?.toInt() ?? 0,
      entityLabel: json['entityLabel'] as String? ?? '',
      entityLink: json['entityLink'] as String?,
      direction: json['direction'] as String? ?? 'OUTBOUND',
      status: json['status'] as String? ?? '',
      subject: json['subject'] as String? ?? '',
      body: json['body'] as String? ?? '',
      from: EmailParticipant.fromJson(_map(json['from']) ?? const {}),
      fromRole: json['fromRole'] as String?,
      fromRoleLabel: json['fromRoleLabel'] as String?,
      to: _maps(json['to']).map(EmailParticipant.fromJson).toList(),
      cc: _maps(json['cc']).map(EmailParticipant.fromJson).toList(),
      unresolved: _maps(json['unresolved']).map(EmailUnresolved.fromJson).toList(),
      sentByName: sentBy?['name'] as String?,
      sentByUserId: (sentBy?['userId'] as num?)?.toInt(),
      deliveredFrom: json['deliveredFrom'] as String?,
      error: json['error'] as String?,
      attempts: (json['attempts'] as num?)?.toInt() ?? 0,
      occurredAt: _date(json['occurredAt']),
      sentAt: _date(json['sentAt']),
      canRetry: json['canRetry'] as bool? ?? false,
      canOpenRecord: json['canOpenRecord'] as bool? ?? false,
      readByMe: json['readByMe'] as bool?,
    );
  }
}

@immutable
class InboxItem {
  final int id;
  final int emailId;
  final EmailEntityType? entityType;
  final int entityId;
  final String entityLabel;
  final String? entityLink;
  final String subject;
  final String snippet;
  final EmailParticipant from;
  final String direction;
  final String status;
  final DateTime? occurredAt;
  final bool read;

  const InboxItem({
    required this.id,
    required this.emailId,
    required this.entityType,
    required this.entityId,
    required this.entityLabel,
    this.entityLink,
    required this.subject,
    required this.snippet,
    required this.from,
    required this.direction,
    required this.status,
    this.occurredAt,
    required this.read,
  });

  factory InboxItem.fromJson(Map<String, dynamic> json) => InboxItem(
        id: (json['id'] as num).toInt(),
        emailId: (json['emailId'] as num).toInt(),
        entityType: EmailEntityType.fromWire(json['entityType'] as String?),
        entityId: (json['entityId'] as num?)?.toInt() ?? 0,
        entityLabel: json['entityLabel'] as String? ?? '',
        entityLink: json['entityLink'] as String?,
        subject: json['subject'] as String? ?? '',
        snippet: json['snippet'] as String? ?? '',
        from: EmailParticipant.fromJson(_map(json['from']) ?? const {}),
        direction: json['direction'] as String? ?? 'OUTBOUND',
        status: json['status'] as String? ?? '',
        occurredAt: _date(json['occurredAt']),
        read: json['read'] as bool? ?? false,
      );
}

String emailStatusLabel(String status) => switch (status) {
      'QUEUED' => 'Queued',
      'SENDING' => 'Sending',
      'SENT' => 'Sent',
      'PARTIAL' => 'Partly sent',
      'FAILED' => 'Failed',
      'NOT_SENT' => 'Not sent',
      'RECEIVED' => 'Received',
      _ => humanizeEnum(status),
    };

String emailProgress(EmailMessage email) {
  final written = formatDateTime(email.occurredAt);
  return switch (email.status) {
    'SENT' => 'sent ${formatDateTime(email.sentAt ?? email.occurredAt)}',
    'RECEIVED' => 'received $written',
    'QUEUED' => 'written $written, waiting to send',
    'SENDING' => 'written $written, sending',
    'PARTIAL' => 'written $written, partly delivered',
    'FAILED' => 'written $written, failed',
    'NOT_SENT' => 'written $written, not delivered',
    _ => email.isInbound ? 'received $written' : 'written $written',
  };
}

String emailOutcomeMessage(EmailMessage email) {
  final error = email.error == null || email.error!.isEmpty ? null : email.error;
  return switch (email.status) {
    'SENT' => 'Email sent',
    'QUEUED' || 'SENDING' => 'Email queued for sending',
    'NOT_SENT' => error == null ? 'Email saved — not sent' : 'Email saved — not sent: $error',
    'PARTIAL' => error == null ? 'Email partly sent' : 'Email partly sent: $error',
    'FAILED' => error == null ? 'Email failed' : 'Email failed: $error',
    _ => 'Email saved',
  };
}

Duration? emailRefreshInterval(Iterable<EmailMessage> emails, {DateTime? now}) {
  final since = (now ?? DateTime.now()).subtract(const Duration(hours: 24));
  var tracking = false;
  for (final email in emails) {
    if (email.status == 'QUEUED' || email.status == 'SENDING') return const Duration(seconds: 5);
    for (final person in email.to) {
      final delivery = person.delivery;
      if (delivery == null) continue;
      if (delivery.inProgress) return const Duration(seconds: 5);
      final sentAt = delivery.sentAt ?? email.sentAt ?? email.occurredAt;
      if (delivery.tracking && sentAt != null && sentAt.isAfter(since)) tracking = true;
    }
  }
  return tracking ? const Duration(seconds: 30) : null;
}
