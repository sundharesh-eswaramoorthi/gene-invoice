import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/as_of.dart';
import '../../core/scope/view_scope.dart';
import '../../shared/models/promise.dart';

@immutable
class PromiseScope {
  final int? customerId;
  final int? invoiceId;
  final int? paymentId;
  const PromiseScope({this.customerId, this.invoiceId, this.paymentId});

  @override
  bool operator ==(Object other) =>
      other is PromiseScope &&
      other.customerId == customerId &&
      other.invoiceId == invoiceId &&
      other.paymentId == paymentId;
  @override
  int get hashCode => Object.hash(customerId, invoiceId, paymentId);
}

final scopedPromisesProvider =
    FutureProvider.autoDispose.family<List<PaymentPromise>, PromiseScope>((ref, scope) async {
  final view = ref.watch(viewScopeProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/promises', queryParameters: {
    'size': 50,
    if (scope.customerId != null) 'customerId': scope.customerId,
    if (scope.invoiceId != null) 'invoiceId': scope.invoiceId,
    if (scope.paymentId != null) 'paymentId': scope.paymentId,
  });
  checkAsOf(res, view.asOf);
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(PaymentPromise.fromJson)
      .toList();
});

final promiseDetailProvider =
    FutureProvider.autoDispose.family<PaymentPromise, int>((ref, id) async {
  final asOf = ref.watch(viewAsOfProvider);
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/promises/$id');
  checkAsOf(res, asOf);
  return PaymentPromise.fromJson((res.data as Map).cast<String, dynamic>());
});

final openPromisesForCustomerProvider =
    FutureProvider.autoDispose.family<List<PaymentPromise>, int>((ref, customerId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/promises', queryParameters: {
    'size': 50,
    'customerId': customerId,
    'filter': ['status:in:OPEN,PARTIALLY_KEPT'],
  });
  return ((res.data as Map)['content'] as List)
      .cast<Map<String, dynamic>>()
      .map(PaymentPromise.fromJson)
      .toList();
});
