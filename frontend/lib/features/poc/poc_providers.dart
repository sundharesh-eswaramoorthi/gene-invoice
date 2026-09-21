import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/privileges.dart';
import '../auth/auth_controller.dart';

enum PocType { SALES, SUCCESS, COLLECTION }

String pocTypeLabel(PocType t) => switch (t) {
      PocType.SALES => 'Sales POC',
      PocType.SUCCESS => 'Customer Success POC',
      PocType.COLLECTION => 'Collection POC',
    };

PocType parsePocType(String? s) =>
    PocType.values.firstWhere((e) => e.name == s, orElse: () => PocType.SALES);

@immutable
class PocUser {
  final int id;
  final String username;
  final String? fullName;
  final String? email;
  final String? role;
  final bool active;

  const PocUser({
    required this.id,
    required this.username,
    this.fullName,
    this.email,
    this.role,
    this.active = true,
  });

  String get display => (fullName != null && fullName!.isNotEmpty) ? fullName! : username;

  factory PocUser.fromJson(Map<String, dynamic> json) => PocUser(
        id: (json['id'] as num).toInt(),
        username: json['username'] as String,
        fullName: json['fullName'] as String?,
        email: json['email'] as String?,
        role: json['role'] as String?,
        active: json['active'] as bool? ?? true,
      );

  @override
  bool operator ==(Object other) => other is PocUser && other.id == id;
  @override
  int get hashCode => id.hashCode;
}

@immutable
class CustomerPoc {
  final int id;
  final PocType pocType;
  final bool primary;
  final PocUser user;

  const CustomerPoc({
    required this.id,
    required this.pocType,
    required this.primary,
    required this.user,
  });

  factory CustomerPoc.fromJson(Map<String, dynamic> json) => CustomerPoc(
        id: (json['id'] as num).toInt(),
        pocType: parsePocType(json['pocType'] as String?),
        primary: json['primary'] as bool? ?? false,
        user: PocUser.fromJson(json['user'] as Map<String, dynamic>),
      );
}

@immutable
class MyPocScope {
  final int userId;
  final bool clearable;
  final bool sales;
  final bool success;
  final bool collection;

  const MyPocScope({
    required this.userId,
    required this.clearable,
    required this.sales,
    required this.success,
    required this.collection,
  });

  bool get isAnyPoc => sales || success || collection;

  factory MyPocScope.fromJson(Map<String, dynamic> json) => MyPocScope(
        userId: (json['userId'] as num).toInt(),
        clearable: json['clearable'] as bool? ?? true,
        sales: json['sales'] as bool? ?? false,
        success: json['success'] as bool? ?? false,
        collection: json['collection'] as bool? ?? false,
      );
}

final canSeePocProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.pocView);
});

final canAssignPocProvider = Provider<bool>((ref) {
  final user = ref.watch(currentUserProvider);
  if (user == null || user.isCustomer) return false;
  return user.has(Privileges.pocAssign);
});

final myPocScopeProvider = FutureProvider<MyPocScope?>((ref) async {
  ref.watch(currentUserProvider.select((u) => u?.id));
  if (!ref.watch(canSeePocProvider)) return null;
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/pocs/my-scope');
  return MyPocScope.fromJson((res.data as Map).cast<String, dynamic>());
});

@immutable
class AssignableQuery {
  final PocType type;
  final String search;
  const AssignableQuery(this.type, this.search);

  @override
  bool operator ==(Object other) =>
      other is AssignableQuery && other.type == type && other.search == search;
  @override
  int get hashCode => Object.hash(type, search);
}

final assignablePocsProvider =
    FutureProvider.autoDispose.family<List<PocUser>, AssignableQuery>((ref, q) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/pocs/assignable', queryParameters: {
    'type': q.type.name,
    if (q.search.isNotEmpty) 'q': q.search,
    'limit': 25,
  });
  return (res.data as List).cast<Map<String, dynamic>>().map(PocUser.fromJson).toList();
});

final customerPocsProvider =
    FutureProvider.autoDispose.family<List<CustomerPoc>, int>((ref, customerId) async {
  final dio = ref.watch(dioProvider);
  final res = await dio.get('/api/customers/$customerId/pocs');
  return (res.data as List).cast<Map<String, dynamic>>().map(CustomerPoc.fromJson).toList();
});
