import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/dispute.dart';
import '../customer_scope/customer_scope.dart';

final disputesProvider = FutureProvider.autoDispose<List<Dispute>>((ref) async {
  final dio = ref.watch(dioProvider);
  final scope = ref.watch(customerScopeProvider);
  final res = await dio.get('/api/disputes');
  final all = (res.data as List).cast<Map<String, dynamic>>().map(Dispute.fromJson).toList();
  if (scope == null) return all;
  return all.where((d) => d.customerId == scope.id).toList();
});

final disputeDetailProvider =
    FutureProvider.autoDispose.family<Dispute, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/disputes/$id');
  return Dispute.fromJson(res.data as Map<String, dynamic>);
});
