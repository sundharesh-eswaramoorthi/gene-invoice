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
