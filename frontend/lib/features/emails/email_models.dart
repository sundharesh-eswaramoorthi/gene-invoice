/// Wire models for the stored in-app Email feature.
library;

/// A From choice: an internal user or a role, with the address known right now (the server
/// re-resolves at send time; a null email means the admin fallback would be used).
class EmailSenderOption {
  final String kind; // USER | ROLE
  final int id;
  final String label;
  final String? email;

  const EmailSenderOption({
    required this.kind,
    required this.id,
    required this.label,
    this.email,
  });

  factory EmailSenderOption.fromJson(Map<String, dynamic> json) => EmailSenderOption(
        kind: json['kind'] as String,
        id: (json['id'] as num).toInt(),
        label: json['label'] as String,
        email: json['email'] as String?,
      );
}

/// A To choice: an internal user or a role. The Customer address is offered separately.
class EmailRecipientOption {
  final String kind; // USER | ROLE
  final int id;
  final String label;
  final String? email;

  const EmailRecipientOption({
    required this.kind,
    required this.id,
    required this.label,
    this.email,
  });

  factory EmailRecipientOption.fromJson(Map<String, dynamic> json) => EmailRecipientOption(
        kind: json['kind'] as String,
        id: (json['id'] as num).toInt(),
        label: json['label'] as String,
        email: json['email'] as String?,
      );
}

class EmailOptions {
  final List<EmailSenderOption> senders;
  final List<EmailRecipientOption> recipients;

  const EmailOptions({required this.senders, required this.recipients});

  factory EmailOptions.fromJson(Map<String, dynamic> json) => EmailOptions(
        senders: ((json['senders'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(EmailSenderOption.fromJson)
            .toList(),
        recipients: ((json['recipients'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(EmailRecipientOption.fromJson)
            .toList(),
      );
}

/// One row of a Customer or Invoice Email tab.
class EmailRecordSummary {
  final int id;
  final String subject;
  final String senderDisplay;
  final DateTime sentAt;
  final String link; // CUSTOMER | INVOICE
  final String? invoiceNumber;
  final int recipientCount;

  const EmailRecordSummary({
    required this.id,
    required this.subject,
    required this.senderDisplay,
    required this.sentAt,
    required this.link,
    this.invoiceNumber,
    required this.recipientCount,
  });

  factory EmailRecordSummary.fromJson(Map<String, dynamic> json) => EmailRecordSummary(
        id: (json['id'] as num).toInt(),
        subject: json['subject'] as String,
        senderDisplay: json['senderDisplay'] as String,
        sentAt: DateTime.parse(json['sentAt'] as String),
        link: json['link'] as String,
        invoiceNumber: json['invoiceNumber'] as String?,
        recipientCount: (json['recipientCount'] as num?)?.toInt() ?? 0,
      );
}

/// One immutable recipient occurrence of a stored Email.
class EmailRecipientDto {
  final String kind; // DIRECT | ROLE | CUSTOMER_ADDRESS
  final String label;
  final String? address;
  final String? roleName;

  const EmailRecipientDto({
    required this.kind,
    required this.label,
    this.address,
    this.roleName,
  });

  factory EmailRecipientDto.fromJson(Map<String, dynamic> json) => EmailRecipientDto(
        kind: json['kind'] as String,
        label: json['label'] as String,
        address: json['address'] as String?,
        roleName: json['roleName'] as String?,
      );

  String get describe => switch (kind) {
        'ROLE' => '$label (via $roleName)',
        'CUSTOMER_ADDRESS' => '$label (customer)',
        _ => label,
      };
}

/// The stored Email itself: what it said and who it reached, frozen at send time.
class EmailDetail {
  final int id;
  final String subject;
  final String? body;
  final String senderDisplay;
  final String? senderAddress;
  final DateTime sentAt;
  final String? sentByDisplay;
  final int? customerId;
  final int? invoiceId;
  final String? invoiceNumber;
  final List<EmailRecipientDto> recipients;

  /// The caller's own read state when they are a recipient, else null.
  final bool? read;

  const EmailDetail({
    required this.id,
    required this.subject,
    this.body,
    required this.senderDisplay,
    this.senderAddress,
    required this.sentAt,
    this.sentByDisplay,
    this.customerId,
    this.invoiceId,
    this.invoiceNumber,
    required this.recipients,
    this.read,
  });

  factory EmailDetail.fromJson(Map<String, dynamic> json) => EmailDetail(
        id: (json['id'] as num).toInt(),
        subject: json['subject'] as String,
        body: json['body'] as String?,
        senderDisplay: json['senderDisplay'] as String,
        senderAddress: json['senderAddress'] as String?,
        sentAt: DateTime.parse(json['sentAt'] as String),
        sentByDisplay: json['sentByDisplay'] as String?,
        customerId: (json['customerId'] as num?)?.toInt(),
        invoiceId: (json['invoiceId'] as num?)?.toInt(),
        invoiceNumber: json['invoiceNumber'] as String?,
        recipients: ((json['recipients'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(EmailRecipientDto.fromJson)
            .toList(),
        read: json['read'] as bool?,
      );
}

/// One row of the caller's Inbox: their read row plus the Email's envelope.
class InboxEntry {
  final int id;
  final int emailId;
  final String subject;
  final String senderDisplay;
  final DateTime sentAt;
  final bool read;

  const InboxEntry({
    required this.id,
    required this.emailId,
    required this.subject,
    required this.senderDisplay,
    required this.sentAt,
    required this.read,
  });

  factory InboxEntry.fromJson(Map<String, dynamic> json) => InboxEntry(
        id: (json['id'] as num).toInt(),
        emailId: (json['emailId'] as num).toInt(),
        subject: json['subject'] as String,
        senderDisplay: json['senderDisplay'] as String,
        sentAt: DateTime.parse(json['sentAt'] as String),
        read: json['read'] as bool? ?? false,
      );
}
