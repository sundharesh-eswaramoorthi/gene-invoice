import 'dart:convert';

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
    final data = _decoded(error.response?.data);
    if (data is Map) {
      // "One or more fields are invalid" gives the user nothing to act on; name each field.
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

/// An error body as the app's `ApiError` shape. A request that asked for bytes — a download —
/// gets its error body in bytes too, because Dio's transformer does what the request asked for
/// whatever came back; decoded here rather than at each call site, so every such request reports
/// the server's own sentence.
Object? _decoded(Object? data) {
  if (data is! List<int>) return data;
  try {
    return jsonDecode(utf8.decode(data));
  } catch (_) {
    // Not the app's JSON: whatever it is, it is not a message to show anyone.
    return null;
  }
}

/// One `fieldErrors` entry as a sentence.
///
/// Bean Validation hands back a fragment that only reads as English with its field in front
/// ("notes" + "must be at most 500 characters"). The app's own refusals are whole sentences
/// already — "Files of this kind cannot be attached (…)", "The file is larger than 10 MB" — and
/// gluing the field on gave "File Files of this kind cannot be attached (…)", so the server's
/// wording and the client's own check on the identical condition did not read the same (AC-C9,
/// UI-03). A message that starts with a lower-case letter is a fragment and is named; anything
/// else is shown exactly as the server wrote it.
String _fieldMessage(String field, String message) {
  final first = message.isEmpty ? '' : message[0];
  final isFragment = first.toUpperCase() != first && first.toLowerCase() == first;
  return isFragment ? '${_fieldLabel(field)} $message' : message;
}

/// A request field's name as a label: "collectionPocUserId" → "Collection poc user id",
/// "items[0].quantity" → "Items[0] quantity".
String _fieldLabel(String path) {
  final words = path
      .replaceAll('.', ' ')
      .replaceAllMapped(RegExp(r'(?<=[a-z0-9])([A-Z])'), (m) => ' ${m[1]!.toLowerCase()}');
  return words.isEmpty ? words : words[0].toUpperCase() + words.substring(1);
}
