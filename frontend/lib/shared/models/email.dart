/// What an email is about.
enum EmailTargetType {
  customer('CUSTOMER', 'customer'),
  invoice('INVOICE', 'invoice');

  const EmailTargetType(this.wire, this.noun);

  final String wire;
  final String noun;
}

/// A user or a role on the From line, or one entry in the To line.
enum EmailPartyType { USER, ROLE, CUSTOMER_EMAIL, unknown }

EmailPartyType _partyType(String? raw) =>
    EmailPartyType.values.firstWhere((t) => t.name == raw, orElse: () => EmailPartyType.unknown);

class EmailSender {
  final EmailPartyType type;
  final int? userId;
  final int? roleId;
  final String name;
  final String? address;

  const EmailSender({
    required this.type,
    this.userId,
    this.roleId,
    required this.name,
    this.address,
  });

  factory EmailSender.fromJson(Map<String, dynamic> json) => EmailSender(
        type: _partyType(json['type'] as String?),
        userId: (json['userId'] as num?)?.toInt(),
        roleId: (json['roleId'] as num?)?.toInt(),
        name: json['name'] as String? ?? '',
        address: json['address'] as String?,
      );
}

/// Someone who was in a role when an email went to that role.
class EmailRoleMember {
  final int userId;
  final String name;
  final String? address;

  const EmailRoleMember({required this.userId, required this.name, this.address});

  factory EmailRoleMember.fromJson(Map<String, dynamic> json) => EmailRoleMember(
        userId: (json['userId'] as num).toInt(),
        name: json['name'] as String? ?? '',
        address: json['address'] as String?,
      );
}

class EmailRecipient {
  final EmailPartyType type;
  final int? userId;
  final int? roleId;

  /// The user's or role's name; null for a customer address.
  final String? name;
  final String? address;

  /// For a role, who was in it at send time.
  final List<EmailRoleMember> members;

  const EmailRecipient({
    required this.type,
    this.userId,
    this.roleId,
    this.name,
    this.address,
    this.members = const [],
  });

  factory EmailRecipient.fromJson(Map<String, dynamic> json) => EmailRecipient(
        type: _partyType(json['type'] as String?),
        userId: (json['userId'] as num?)?.toInt(),
        roleId: (json['roleId'] as num?)?.toInt(),
        name: json['name'] as String?,
        address: json['address'] as String?,
        members: ((json['members'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(EmailRoleMember.fromJson)
            .toList(),
      );
}

class EmailMessage {
  final int id;
  final EmailTargetType targetType;
  final int customerId;
  final String customerName;
  final int? invoiceId;
  final String? invoiceNumber;
  final EmailSender from;
  final List<EmailRecipient> to;
  final String subject;
  final String body;
  final int sentByUserId;
  final String sentByName;
  final DateTime sentAt;

  const EmailMessage({
    required this.id,
    required this.targetType,
    required this.customerId,
    required this.customerName,
    this.invoiceId,
    this.invoiceNumber,
    required this.from,
    required this.to,
    required this.subject,
    required this.body,
    required this.sentByUserId,
    required this.sentByName,
    required this.sentAt,
  });

  List<EmailRecipient> get users => to.where((r) => r.type == EmailPartyType.USER).toList();
  List<EmailRecipient> get roles => to.where((r) => r.type == EmailPartyType.ROLE).toList();
  List<EmailRecipient> get customerAddresses =>
      to.where((r) => r.type == EmailPartyType.CUSTOMER_EMAIL).toList();

  factory EmailMessage.fromJson(Map<String, dynamic> json) => EmailMessage(
        id: (json['id'] as num).toInt(),
        targetType: json['targetType'] == EmailTargetType.invoice.wire
            ? EmailTargetType.invoice
            : EmailTargetType.customer,
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String? ?? '',
        invoiceId: (json['invoiceId'] as num?)?.toInt(),
        invoiceNumber: json['invoiceNumber'] as String?,
        from: EmailSender.fromJson(json['from'] as Map<String, dynamic>),
        to: ((json['to'] as List?) ?? const [])
            .cast<Map<String, dynamic>>()
            .map(EmailRecipient.fromJson)
            .toList(),
        subject: json['subject'] as String? ?? '',
        body: json['body'] as String? ?? '',
        sentByUserId: (json['sentByUserId'] as num).toInt(),
        sentByName: json['sentByName'] as String? ?? '',
        sentAt: DateTime.parse(json['sentAt'] as String),
      );
}

/// An email in the signed-in user's Inbox, with their own read status.
class InboxItem {
  final int id;
  final bool read;
  final DateTime? readAt;
  final EmailMessage email;

  const InboxItem({required this.id, required this.read, this.readAt, required this.email});

  factory InboxItem.fromJson(Map<String, dynamic> json) => InboxItem(
        id: (json['id'] as num).toInt(),
        read: json['read'] as bool? ?? false,
        readAt: json['readAt'] == null ? null : DateTime.parse(json['readAt'] as String),
        email: EmailMessage.fromJson(json['email'] as Map<String, dynamic>),
      );
}

/// An internal user the compose form offers as a sender or recipient.
class StaffOption {
  final int id;
  final String username;
  final String? fullName;
  final String? email;
  final String? role;

  const StaffOption({required this.id, required this.username, this.fullName, this.email, this.role});

  String get display => (fullName != null && fullName!.isNotEmpty) ? fullName! : username;

  factory StaffOption.fromJson(Map<String, dynamic> json) => StaffOption(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        fullName: json['fullName'] as String?,
        email: json['email'] as String?,
        role: json['role'] as String?,
      );

  @override
  bool operator ==(Object other) => other is StaffOption && other.id == id;
  @override
  int get hashCode => id.hashCode;
}

/// A role the compose form offers, with its mailbox and how many people it reaches now.
class RoleOption {
  final int id;
  final String name;
  final String? email;
  final int memberCount;

  const RoleOption({required this.id, required this.name, this.email, required this.memberCount});

  factory RoleOption.fromJson(Map<String, dynamic> json) => RoleOption(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        email: json['email'] as String?,
        memberCount: (json['memberCount'] as num?)?.toInt() ?? 0,
      );

  @override
  bool operator ==(Object other) => other is RoleOption && other.id == id;
  @override
  int get hashCode => id.hashCode;
}

/// Every address of the customer an email about this customer or invoice can go to.
class EmailAddresses {
  final int customerId;
  final String customerName;
  final int? invoiceId;
  final String? invoiceNumber;
  final List<String> addresses;

  const EmailAddresses({
    required this.customerId,
    required this.customerName,
    this.invoiceId,
    this.invoiceNumber,
    required this.addresses,
  });

  factory EmailAddresses.fromJson(Map<String, dynamic> json) => EmailAddresses(
        customerId: (json['customerId'] as num).toInt(),
        customerName: json['customerName'] as String? ?? '',
        invoiceId: (json['invoiceId'] as num?)?.toInt(),
        invoiceNumber: json['invoiceNumber'] as String?,
        addresses: ((json['addresses'] as List?) ?? const []).map((e) => e.toString()).toList(),
      );
}
