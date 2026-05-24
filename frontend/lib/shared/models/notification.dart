class AppNotification {
  final int id;
  final String type;
  final String title;
  final String? message;
  final String? link;
  final bool read;
  final DateTime createdAt;

  const AppNotification({
    required this.id,
    required this.type,
    required this.title,
    required this.message,
    required this.link,
    required this.read,
    required this.createdAt,
  });

  factory AppNotification.fromJson(Map<String, dynamic> json) => AppNotification(
        id: (json['id'] as num).toInt(),
        type: json['type'] as String,
        title: json['title'] as String,
        message: json['message'] as String?,
        link: json['link'] as String?,
        read: json['read'] as bool? ?? false,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}
