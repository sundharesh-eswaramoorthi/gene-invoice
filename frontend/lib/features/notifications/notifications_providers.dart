import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../shared/models/notification.dart';

// Notifications answer live whatever the date, but only for the regions asked about (§5.6.7); a
// change of regions asks again, and restarts the count's polling.
final notificationsProvider = FutureProvider.autoDispose<List<AppNotification>>((ref) async {
  ref.watch(viewRegionsProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notifications');
  return (res.data as List).cast<Map<String, dynamic>>().map(AppNotification.fromJson).toList();
});

final unreadCountProvider = StreamProvider.autoDispose<int>((ref) {
  ref.watch(viewRegionsProvider);
  return pollUnreadCount(ref, ref.watch(dioProvider), '/api/notifications/unread-count');
});

Stream<int> pollUnreadCount(Ref ref, Dio dio, String path) {
  final counts = StreamController<int>();
  Future<void> fetch() async {
    try {
      final res = await dio.get(path);
      if (!counts.isClosed) counts.add(((res.data as Map)['count'] as num).toInt());
    } catch (_) {
    }
  }

  final timer = Timer.periodic(const Duration(seconds: 30), (_) => fetch());
  ref.onDispose(() {
    timer.cancel();
    counts.close();
  });
  unawaited(fetch());
  return counts.stream;
}
