import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/auth/auth_controller.dart';
import '../storage/secure_storage.dart';

/// Default API base URL. Override at build time with:
///   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
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
    onError: (e, handler) async {
      if (kDebugMode) {
        debugPrint('API error ${e.response?.statusCode} ${e.requestOptions.uri}: ${e.response?.data}');
      }
      if (_isExpiredSession(e)) {
        // The token has expired or is no longer valid. Signing out sends the router back to the
        // login screen, rather than leaving every screen failing on a dead session.
        await ref.read(authControllerProvider.notifier).logout();
      }
      handler.next(e);
    },
  ));

  return dio;
});

/// A 401 means the session is gone — except from the sign-in calls themselves (login, change
/// password), which use it to report wrong credentials.
bool _isExpiredSession(DioException e) {
  if (e.response?.statusCode != 401) return false;
  final path = e.requestOptions.path;
  return !path.startsWith('/api/auth/') || path == '/api/auth/me';
}

String apiErrorMessage(Object error) {
  if (error is DioException) {
    final data = error.response?.data;
    if (data is Map && data['message'] is String) return data['message'] as String;
    if (error.message != null) return error.message!;
    return 'Network error';
  }
  return error.toString();
}
