import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/auth_controller.dart';
import '../storage/secure_storage.dart';

const String _defaultBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8080',
);

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage.create());

final dioProvider = Provider<Dio>((ref) {
  final storage = ref.watch(tokenStorageProvider);
  final dio = Dio(BaseOptions(
    baseUrl: _defaultBaseUrl,
    connectTimeout: const Duration(seconds: 15),
    receiveTimeout: const Duration(seconds: 30),
    contentType: 'application/json',
  ));

  dio.interceptors.add(InterceptorsWrapper(
    onRequest: (options, handler) async {
      final token = await storage.read();
      if (token != null) {
        options.headers['Authorization'] = 'Bearer $token';
      }
      handler.next(options);
    },
    onResponse: pendingApprovalOnResponse,
    onError: (e, handler) async {
      if (kDebugMode) {
        debugPrint('API error ${e.response?.statusCode} ${e.requestOptions.uri}: ${e.response?.data}');
      }
      if (_isExpiredSession(e)) {
        await ref.read(authControllerProvider.notifier).logout();
      }
      handler.next(e);
    },
  ));

  return dio;
});

bool _isExpiredSession(DioException e) {
  if (e.response?.statusCode != 401) return false;
  final path = e.requestOptions.path;
  return !path.startsWith('/api/auth/') || path == '/api/auth/me';
}

/// A save the server took but did not carry out: it is waiting for a second pair of eyes (B2).
///
/// This is the 202 body of ApprovalDtos.Accepted as it lands in Dart. Every field is read
/// defensively, because the one thing this must never do is throw inside an interceptor and turn
/// "your payment needs approval" into an unexplained network error.
class PendingApproval {
  final int? pendingChangeId;
  final String? action;
  final String? targetType;
  final int? targetId;
  final int? customerId;
  final int? regionId;
  final String? regionName;
  final String? summary;
  final double? exposure;
  final double? thresholdApplied;
  final DateTime? requestedAt;

  /// The endpoint the maker called, as the server saw it — /api/payments.
  final String? path;

  /// Where the change now lives IN THIS APP, already in Flutter's own route space —
  /// /approvals/4127. The server builds it, so one place decides what the queue is called.
  final String? link;

  /// The sentence to show the maker. apiErrorMessage already reads data['message'], so every
  /// screen renders this in the slot it already has before any per-screen work (B2).
  final String message;

  const PendingApproval({
    required this.pendingChangeId,
    required this.action,
    this.targetType,
    this.targetId,
    this.customerId,
    this.regionId,
    this.regionName,
    this.summary,
    this.exposure,
    this.thresholdApplied,
    this.requestedAt,
    this.path,
    this.link,
    required this.message,
  });

  /// The route to the change, whatever the server said. A body that carried no link still has to
  /// take the maker somewhere, and the id is enough to build it (B2).
  String? get route =>
      link ?? (pendingChangeId == null ? null : '/approvals/$pendingChangeId');

  factory PendingApproval.fromJson(Map<String, dynamic> json) => PendingApproval(
        pendingChangeId: (json['pendingChangeId'] as num?)?.toInt(),
        action: json['action']?.toString(),
        targetType: json['targetType']?.toString(),
        targetId: (json['targetId'] as num?)?.toInt(),
        customerId: (json['customerId'] as num?)?.toInt(),
        regionId: (json['regionId'] as num?)?.toInt(),
        regionName: json['regionName'] as String?,
        summary: json['summary'] as String?,
        exposure: (json['exposure'] as num?)?.toDouble(),
        thresholdApplied: (json['thresholdApplied'] as num?)?.toDouble(),
        requestedAt: json['requestedAt'] == null
            ? null
            : DateTime.tryParse(json['requestedAt'].toString()),
        path: json['path'] as String?,
        link: json['link'] as String?,
        message: json['message'] as String? ??
            'This change has been sent for approval and has not taken effect.',
      );
}

/// The one spelling of the outcome string. A client matches on this and on nothing else (B2).
const String pendingApprovalOutcome = 'PENDING_APPROVAL';

/// Turns the 202 a held save comes back with into an error, ONCE, for every save path in the app.
///
/// Dio's default validateStatus accepts 200-299, so without this a 202 is a SUCCESS: every
/// existing save path falls into its success branch and parses the held change as the record it
/// asked for — a maker would be told their payment was recorded when nothing happened. It is a
/// top-level function and not a closure so the widget tests can drive production code rather than
/// a copy of it (B2).
///
/// reject(err) with NO second argument: the following error interceptor is api_client's own
/// onError, whose only jobs are a debug print and a 401 logout, and neither should run here.
void pendingApprovalOnResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
  final data = response.data;
  if (response.statusCode == 202 && data is Map && data['outcome'] == pendingApprovalOutcome) {
    handler.reject(DioException(
      requestOptions: response.requestOptions,
      response: response,
      type: DioExceptionType.badResponse,
      error: PendingApproval.fromJson(Map<String, dynamic>.from(data)),
    ));
    return;
  }
  handler.next(response);
}

/// The held change behind a failed call, or null when the call failed for any other reason. Every
/// save path asks this first and treats an answer as "nothing happened, and somebody has to
/// approve it" rather than as a failure (B2).
PendingApproval? pendingApprovalOf(DioException e) {
  final held = e.error;
  return held is PendingApproval ? held : null;
}

String apiErrorMessage(Object error) {
  if (error is DioException) {
    final data = _decoded(error.response?.data);
    if (data is Map) {
      final fields = data['fieldErrors'];
      if (fields is Map && fields.isNotEmpty) {
        return fields.entries.map((e) => _fieldMessage('${e.key}', '${e.value}')).join('\n');
      }
      if (data['message'] is String) return data['message'] as String;
    }
    if (error.message != null) return error.message!;
    return 'Network error';
  }
  return error.toString();
}

Object? _decoded(Object? data) {
  if (data is! List<int>) return data;
  try {
    return jsonDecode(utf8.decode(data));
  } catch (_) {
    return null;
  }
}

String _fieldMessage(String field, String message) {
  final first = message.isEmpty ? '' : message[0];
  final isFragment = first.toUpperCase() != first && first.toLowerCase() == first;
  return isFragment ? '${_fieldLabel(field)} $message' : message;
}

String _fieldLabel(String path) {
  final words = path
      .replaceAll('.', ' ')
      .replaceAllMapped(RegExp(r'(?<=[a-z0-9])([A-Z])'), (m) => ' ${m[1]!.toLowerCase()}');
  return words.isEmpty ? words : words[0].toUpperCase() + words.substring(1);
}
