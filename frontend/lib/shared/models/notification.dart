/// One structured invoice snapshot attached to a strategy notification.
class NotificationInvoiceItem {
  final int invoiceId;
  final String invoiceNumber;

  const NotificationInvoiceItem({
    required this.invoiceId,
    required this.invoiceNumber,
  });

  factory NotificationInvoiceItem.fromJson(Map<String, dynamic> json) =>
      NotificationInvoiceItem(
        invoiceId: (json['invoiceId'] as num).toInt(),
        invoiceNumber: json['invoiceNumber'] as String,
      );
}

class AppNotification {
  final int id;
  final String type;
  final String title;
  final String? message;
  final String? link;
  final bool read;
  final DateTime createdAt;
  final List<NotificationInvoiceItem>? invoiceItems;

  const AppNotification({
    required this.id,
    required this.type,
    required this.title,
    required this.message,
    required this.link,
    required this.read,
    required this.createdAt,
    this.invoiceItems,
  });

  factory AppNotification.fromJson(Map<String, dynamic> json) => AppNotification(
        id: (json['id'] as num).toInt(),
        type: json['type'] as String,
        title: json['title'] as String,
        message: json['message'] as String?,
        link: json['link'] as String?,
        read: json['read'] as bool? ?? false,
        createdAt: DateTime.parse(json['createdAt'] as String),
        invoiceItems: (json['invoiceItems'] as List?)
            ?.map((e) =>
                NotificationInvoiceItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}
