import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/notification_strategy.dart';

/// Strategy HTTP calls ride the same authenticated Dio client as every other feature —
/// the JWT interceptor in [dioProvider] is unchanged.
final strategiesProvider =
    FutureProvider.autoDispose<List<NotificationStrategy>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notification-strategies');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(NotificationStrategy.fromJson)
      .toList();
});

/// The authoritative invoice statuses selectable on a strategy, loaded from the backend.
final strategyStatusesProvider = FutureProvider.autoDispose<List<String>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notification-strategies/invoice-statuses');
  return (res.data as List)
      .map((e) => (e as Map<String, dynamic>)['name'].toString())
      .toList();
});

/// Non-customer accounts that may be chosen as additional recipients; customer-role users
/// are never among them.
final strategyRecipientsProvider =
    FutureProvider.autoDispose<List<StrategyRecipientOption>>((ref) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/notification-strategies/eligible-recipients');
  return (res.data as List)
      .cast<Map<String, dynamic>>()
      .map(StrategyRecipientOption.fromJson)
      .toList();
});
