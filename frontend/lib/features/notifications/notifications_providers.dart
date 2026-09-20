import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/notification.dart';

final notificationsProvider = FutureProvider.autoDispose<List<AppNotification>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notifications');
  return (res.data as List).cast<Map<String, dynamic>>().map(AppNotification.fromJson).toList();
});

final unreadCountProvider = StreamProvider.autoDispose<int>(
    (ref) => pollUnreadCount(ref, ref.watch(dioProvider), '/api/notifications/unread-count'));

/// A badge's count from [path] (`{"count": n}`): fetched now, then every 30 seconds for as long as
/// [ref]'s provider lives. A failed fetch is skipped and the next tick tries again, the first one
/// included: an error that ended the stream would end the polling with it. The timer stops with
/// the provider, so no request outlives it; one that did would go out after a sign-out without a
/// token, and its 401 would sign out a second time.
Stream<int> pollUnreadCount(Ref ref, Dio dio, String path) {
  final counts = StreamController<int>();
  Future<void> fetch() async {
    try {
      final res = await dio.get(path);
      if (!counts.isClosed) counts.add(((res.data as Map)['count'] as num).toInt());
    } catch (_) {
      // ignore transient errors
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
