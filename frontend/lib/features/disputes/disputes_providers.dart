import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/dispute.dart';

/// Disputes attached to one record, or all of one customer's.
@immutable
class DisputeScope {
  final DisputeTargetType? targetType;
  final int? targetId;
  final int? customerId;

  const DisputeScope({this.targetType, this.targetId, this.customerId});

  @override
  bool operator ==(Object other) =>
      other is DisputeScope &&
      other.targetType == targetType &&
      other.targetId == targetId &&
      other.customerId == customerId;

  @override
  int get hashCode => Object.hash(targetType, targetId, customerId);
}

final scopedDisputesProvider =
    FutureProvider.autoDispose.family<List<Dispute>, DisputeScope>((ref, scope) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/disputes', queryParameters: {
    'size': 50,
    if (scope.targetType != null) 'targetType': scope.targetType!.name,
    if (scope.targetId != null) 'targetId': scope.targetId,
    if (scope.customerId != null) 'customerId': scope.customerId,
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(Dispute.fromJson)
      .toList();
});

final disputeDetailProvider =
    FutureProvider.autoDispose.family<Dispute, int>((ref, id) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/disputes/$id');
  return Dispute.fromJson(res.data as Map<String, dynamic>);
});
