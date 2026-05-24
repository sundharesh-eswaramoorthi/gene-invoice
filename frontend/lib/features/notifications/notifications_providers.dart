import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/notification.dart';

final notificationsProvider = FutureProvider.autoDispose<List<AppNotification>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notifications');
  return (res.data as List).cast<Map<String, dynamic>>().map(AppNotification.fromJson).toList();
});

final unreadCountProvider = StreamProvider.autoDispose<int>((ref) async* {
  final dio = ref.watch(dioProvider);
  Future<int> fetch() async {
    final res = await dio.get('/api/notifications/unread-count');
    return ((res.data as Map)['count'] as num).toInt();
  }

  yield await fetch();
  await for (final _ in Stream.periodic(const Duration(seconds: 30))) {
    try {
      yield await fetch();
    } catch (_) {
      // ignore transient errors
    }
  }
});
