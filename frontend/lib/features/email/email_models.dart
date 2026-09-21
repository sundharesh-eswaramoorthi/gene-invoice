import 'package:flutter/foundation.dart';

import '../../core/format.dart';
import '../documents/document_models.dart' show documentKindLabel, formatBytes;
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

// ---- addressing ---------------------------------------------------------------------

/// Which people a role token means (L1): everyone holding that seat on the record's customer, or
/// the one person the record itself stores.
abstract final class EmailRoleLevel {
  static const customer = 'CUSTOMER';
  static const record = 'RECORD';
}

/// One From or To entry in a send request (§5). Value equality, so the same role or person is
/// never added twice; the same role at the two levels is two entries, not one.
@immutable
class EmailToken {
  /// USER, ROLE or CUSTOMER.
  final String type;
  final int? userId;

  /// An EmailRole key such as COLLECTION_POC.
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

/// One way a recipient came to be on an email.
@immutable
class EmailSource {
  /// USER, ROLE, CUSTOMER, MAILBOX or HEADER.
  final String type;
  final String? role;

  /// An [EmailRoleLevel] on a ROLE source (L1); null on any other, and on a role written before
  /// levels, which kept none — the server names it by the role alone rather than claiming one (L7).
  final String? level;

  /// The level is already in the server's words here — "Sales POC (this invoice)" — so this is
  /// what the app prints.
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

/// What became of one To recipient's own copy of an outbound email (mail-service.md M8). Gmail
/// sends no receipts, so Sent is Gmail accepting it; Delivered is the copy seen in the
/// recipient's own connected Gmail ([deliveredConfirmed]), or else no bounce within 15 minutes;
/// Read is known only for a recipient whose own Gmail is connected.
@immutable
class RecipientDelivery {
  /// QUEUED, SENDING, SENT, DELIVERED, READ, BOUNCED, FAILED or NOT_SENT. Null when there is no
  /// copy: the recipient has no address, or the email was sent before copies were tracked.
  final String? status;

  /// Why it bounced, failed or was not sent. A customer viewer gets "Could not be delivered" in
  /// place of the provider's text.
  final String? error;
  final DateTime? sentAt;
  final DateTime? deliveredAt;
  final bool deliveredConfirmed;

  /// Read in Gmail.
  final DateTime? readAt;
  final DateTime? bouncedAt;

  /// Read in the app's Inbox, which only a recipient with a login has.
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

  /// Still on its way out of the service.
  bool get inProgress => status == 'QUEUED' || status == 'SENDING';

  /// Out, but a read or a bounce may still change it.
  bool get tracking => status == 'SENT' || status == 'DELIVERED';
}

/// "Delivered 17 Sep 2026, 6:07 AM (estimated)", "Bounced: 550 5.1.1 …": a copy's status as the
/// Email tab shows it after the recipient's name. Null when there is no copy to speak of.
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

  /// What became of this recipient's own copy. Null for masked people, received mail, and
  /// recipients of older emails whom nothing more is known about.
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

  /// "Bob Smith <bob@company.com>", or the name alone when there is no address to show.
  String get display {
    if (address == null || address!.isEmpty) return name.isEmpty ? '—' : name;
    if (name.isEmpty || name == address) return address!;
    return '$name <$address>';
  }

  /// "added directly, Collection POC". A masked person is already named by their role, so that
  /// role is not repeated after it.
  String get howAdded => sources
      .map((s) => s.description)
      .where((d) => d != name)
      .toSet()
      .join(', ');
}

/// A role offered on the record at one level, and who holds it right now (§4). In To a role
/// reaches everyone who holds it; as From it is one person, since an email has one sender.
@immutable
class EmailRoleOption {
  final String role;
  final String label;

  /// An [EmailRoleLevel]: the customer's POC book, or what this record stores (L1).
  final String level;

  /// "Customer" at customer level, else the record's noun: "Invoice", "Payment".
  final String levelLabel;

  /// What the form calls the group of roles at this level: "Customer level", "Invoice level".
  final String groupLabel;

  /// Null when there is no record to resolve against — the list and bulk compose.
  final bool? resolved;

  /// Everyone the role reaches in To, primary first. Empty when unresolved, without a record, or
  /// masked from this viewer.
  final List<EmailPerson> people;

  /// The one person who sends when this role is the From: the primary, else the next active
  /// holder. Null in the same cases as an empty [people].
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

  /// The From or To entry that picks this role at this level.
  EmailToken get token => EmailToken.role(role, level: level);

  /// The role with its level named, in the server's own words for a source (§3): "Sales POC
  /// (customer)", "Sales POC (this invoice)". For where the group heading is not there to say it.
  String get labelWithLevel => level == EmailRoleLevel.record
      ? '$label (this ${levelLabel.toLowerCase()})'
      : '$label (customer)';
}

/// The roles of one level as the compose form lists them: a heading and the roles under it (§4).
@immutable
class EmailRoleGroup {
  /// An [EmailRoleLevel].
  final String level;

  /// "Customer level", "Invoice level".
  final String label;
  final List<EmailRoleOption> roles;

  const EmailRoleGroup({required this.level, required this.label, required this.roles});
}

/// A person as the compose form lists them: the caller, a role holder or a people-search hit.
@immutable
class EmailPerson {
  final int? userId;
  final String name;
  final String? username;
  final String? email;

  /// Their Gmail connection ([GmailStatus]): only a connected sender's email leaves the app
  /// (mail-service.md M5). Null when masked.
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

  /// Known not to have a working Gmail connection, so email from them is saved but not sent.
  bool get gmailNotConnected => gmail != null && gmail != GmailStatus.connected;
}

/// The API's values for a user's Gmail connection (mail-service.md §5.3).
abstract final class GmailStatus {
  static const connected = 'CONNECTED';
  static const needsReconnect = 'NEEDS_RECONNECT';
  static const notConnected = 'NOT_CONNECTED';
}

/// A user's own Gmail connection (GET /api/me/gmail). The app's copy of it — for another user
/// (GET /api/users/{id}/gmail) or in the delivery status — has only some of these fields.
@immutable
class GmailConnection {
  /// False when the app has no mail service, so there is nothing to connect to. Only
  /// /api/me/gmail says; the shorter shapes leave it out and read as configured.
  final bool configured;

  /// A [GmailStatus] value.
  final String status;
  final String? gmailAddress;

  /// Not secret, so the form can offer it again for a reconnect. The client secret and the
  /// refresh token never come back.
  final String? clientId;

  /// Why the connection needs renewing.
  final String? reason;
  final DateTime? connectedAt;

  /// When the mailbox was last read, and why the last read failed.
  final DateTime? lastSyncedAt;
  final String? lastSyncError;

  /// Set when the mail service could not be reached and this is the app's last known copy.
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

  /// There is a connection, working or not, so the form offers Reconnect and Disconnect.
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

/// Whether mail really leaves the app (mail-service.md M13), and the caller's own Gmail.
@immutable
class EmailDelivery {
  final bool configured;

  /// The caller's own connection (GET /api/emails/delivery). Null for customer logins, who do
  /// not connect Gmail, and in the compose context, which says only [configured].
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

// ---- attachments and placeholders ----------------------------------------------------

/// How many documents one email may carry (`EmailAttachments.MAX_ATTACHMENTS`). Mirrored here so
/// the picker stops offering more at the same count the server refuses at, in its own words.
const int maxEmailAttachments = 10;

/// A document the compose form may offer for attaching (AttachableDto, E17): one already on the
/// record, or on its customer. [id] is what goes out in the send request's `documentIds`.
@immutable
class EmailAttachable {
  final int id;
  final String filename;
  final String contentType;
  final int sizeBytes;

  /// RECORD or CUSTOMER — where the document was found.
  final String source;

  /// The heading the form groups it under, in the server's words: "This invoice", "Customer".
  final String sourceLabel;

  const EmailAttachable({
    required this.id,
    required this.filename,
    this.contentType = '',
    this.sizeBytes = 0,
    this.source = 'RECORD',
    this.sourceLabel = '',
  });

  factory EmailAttachable.fromJson(Map<String, dynamic> json) => EmailAttachable(
        id: (json['id'] as num).toInt(),
        filename: json['filename'] as String? ?? '',
        contentType: json['contentType'] as String? ?? '',
        sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
        source: json['source'] as String? ?? 'RECORD',
        sourceLabel: json['sourceLabel'] as String? ?? '',
      );

  /// "PDF", "PNG", … — the documents feature's own wording, so a file reads the same in the
  /// attach picker as it does on the Documents tab.
  String get kindLabel => documentKindLabel(contentType, filename);

  /// "1.5 MB", in the units the Documents tab writes a size in.
  String get size => formatBytes(sizeBytes);

  /// "PDF · 1.5 MB", the line under a filename in the picker.
  String get details => '$kindLabel · $size';
}

/// A document that went out with an email (AttachmentDto, E17). It is the email's own snapshot of
/// the file: [documentId] may point at a document that has since been deleted, which is why the
/// name, type and size are kept here rather than read back off it.
@immutable
class EmailAttachment {
  final int id;
  final int? documentId;
  final String filename;
  final String contentType;
  final int sizeBytes;

  const EmailAttachment({
    required this.id,
    this.documentId,
    required this.filename,
    this.contentType = '',
    this.sizeBytes = 0,
  });

  factory EmailAttachment.fromJson(Map<String, dynamic> json) => EmailAttachment(
        id: (json['id'] as num).toInt(),
        documentId: (json['documentId'] as num?)?.toInt(),
        filename: json['filename'] as String? ?? '',
        contentType: json['contentType'] as String? ?? '',
        sizeBytes: (json['sizeBytes'] as num?)?.toInt() ?? 0,
      );

  String get kindLabel => documentKindLabel(contentType, filename);
  String get size => formatBytes(sizeBytes);
  String get details => '$kindLabel · $size';
}

/// One placeholder a subject or body may carry (PlaceholderDto, M4). [key] is the whole token,
/// braces and all — `{{Customer.Name}}` — so inserting it is a plain paste of what the server
/// said. [sample] is what it fills in on *this* record, which is the reason to offer it at all:
/// the writer reads what the email will say before sending it. An empty sample means the record
/// has nothing there, and the email will read with a gap in that place.
@immutable
class EmailPlaceholder {
  final String key;
  final String label;
  final String sample;

  const EmailPlaceholder({required this.key, required this.label, this.sample = ''});

  factory EmailPlaceholder.fromJson(Map<String, dynamic> json) => EmailPlaceholder(
        key: json['key'] as String? ?? '',
        label: json['label'] as String? ?? '',
        sample: json['sample'] as String? ?? '',
      );
}

/// The placeholders of one level — "Customer", then "Invoice" on an invoice (M1).
@immutable
class EmailPlaceholderGroup {
  final String label;
  final List<EmailPlaceholder> placeholders;

  const EmailPlaceholderGroup({required this.label, this.placeholders = const []});

  factory EmailPlaceholderGroup.fromJson(Map<String, dynamic> json) => EmailPlaceholderGroup(
        label: json['label'] as String? ?? '',
        placeholders: _maps(json['placeholders']).map(EmailPlaceholder.fromJson).toList(),
      );
}

/// Everything the compose form needs about one record, or about a type when no record is chosen
/// yet (GET /api/emails/context).
@immutable
class EmailContext {
  final EmailEntityType? entityType;
  final int? entityId;
  final String? entityLabel;
  final String? entityLink;
  final EmailDelivery delivery;

  /// A customer login: From is always themselves, and To offers roles and customer emails only.
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

  /// The role a From or To token names, or null when this record does not offer it.
  EmailRoleOption? roleOf(EmailToken token) =>
      token.isRole ? role(token.role!, level: token.level) : null;

  /// The roles in the labelled groups the form shows them in, in the server's order: the
  /// customer's POC book first, then the record's own fields (§4). A level with no roles is not
  /// a group at all.
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

/// A role that resolved to nobody when the email was sent or previewed.
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

/// What Send would do with the form as it stands (POST /api/emails/preview).
@immutable
class EmailPreview {
  final EmailParticipant? from;
  final List<EmailParticipant> to;
  final List<EmailUnresolved> unresolved;

  /// What would make Send fail. Send stays disabled while any is listed.
  final List<String> problems;

  /// Why the email would be saved but not sent — no mail service, a sender without a working
  /// Gmail, or attachments the mail service cannot carry. Send stays enabled: the email is still
  /// worth keeping. They are shown exactly as the server words them, never rewritten here: the
  /// server is the one that knows what will really happen to the email.
  final List<String> warnings;

  /// The subject and body with this record's placeholders filled in — what would actually be
  /// stored and read (M3). Null on an older server, and on a preview that never got that far.
  final String? subject;
  final String? body;

  const EmailPreview({
    required this.from,
    required this.to,
    required this.unresolved,
    required this.problems,
    this.warnings = const [],
    this.subject,
    this.body,
  });

  factory EmailPreview.fromJson(Map<String, dynamic> json) => EmailPreview(
        from: _map(json['from']) == null ? null : EmailParticipant.fromJson(_map(json['from'])!),
        to: _maps(json['to']).map(EmailParticipant.fromJson).toList(),
        unresolved: _maps(json['unresolved']).map(EmailUnresolved.fromJson).toList(),
        problems: ((json['problems'] as List?) ?? const []).map((e) => '$e').toList(),
        warnings: ((json['warnings'] as List?) ?? const []).map((e) => '$e').toList(),
        subject: json['subject'] as String?,
        body: json['body'] as String?,
      );

  /// Whether filling the placeholders in changed anything against what was typed. When it did
  /// not, the filled-in text is the very text on the form a few lines above, and showing it
  /// again says nothing; when it did, it is the whole point of the preview (M3).
  bool differsFrom({required String typedSubject, required String typedBody}) =>
      (subject != null && subject != typedSubject) || (body != null && body != typedBody);
}

// ---- saved emails -------------------------------------------------------------------

/// Why an email a customer login wrote was never handed to Gmail, in the server's own words
/// (`EmailDispatcher.CUSTOMER_SENDER`). It is saved for everyone on it to read, and that is all
/// that can ever happen to it (mail-service.md M13).
const notSentFromCustomerLogin = 'Email from a customer login is not sent through Gmail';

/// One saved email (EmailDto).
@immutable
class EmailMessage {
  final int id;
  final EmailEntityType? entityType;
  final int entityId;
  final String entityLabel;
  final String? entityLink;

  /// OUTBOUND or INBOUND.
  final String direction;

  /// QUEUED, SENDING, SENT, PARTIAL, FAILED, NOT_SENT or RECEIVED. An outbound email's status
  /// sums up its recipients' copies (mail-service.md M12).
  final String status;
  final String subject;
  final String body;
  final EmailParticipant from;
  final String? fromRole;
  final String? fromRoleLabel;
  final List<EmailParticipant> to;
  final List<EmailParticipant> cc;
  final List<EmailUnresolved> unresolved;

  /// Who pressed Send. Null for received mail.
  final String? sentByName;
  final int? sentByUserId;
  final String? deliveredFrom;
  final String? error;
  final int attempts;
  final DateTime? occurredAt;
  final DateTime? sentAt;
  final bool canRetry;

  /// Whether this viewer may open the record it is about. A sender or recipient may read an email
  /// about a record they cannot see, and a link to that record would only lead to a refusal.
  final bool canOpenRecord;

  /// Null when this viewer is not one of its To recipients.
  final bool? readByMe;

  /// What the sender attached, in the order they chose it; empty for most email (E17).
  final List<EmailAttachment> attachments;

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
    this.attachments = const [],
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
      attachments: _maps(json['attachments']).map(EmailAttachment.fromJson).toList(),
    );
  }
}

/// One row of the caller's inbox: a To recipient row of theirs (InboxItemDto).
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

/// When the email was written or sent and what became of it, by its status: "sent 17 Sep 2026,
/// 6:07 AM", "written …, failed". Only a sent email has a send time; the rest are told apart by
/// status, so a failed one no longer reads "not sent yet" as though it were still on its way.
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

/// The snackbar after a send or a retry, by the status the server settled on. The server answers
/// once the mail service has the email, so it is usually still on its way.
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

/// How often a view of [emails] asks for them again, or null when nothing on it can still change
/// (mail-service.md §6): every 5 seconds while an email or a copy is on its way out, every 30
/// while a copy went out in the last day — a read or a bounce may still come — and not at all
/// otherwise.
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
