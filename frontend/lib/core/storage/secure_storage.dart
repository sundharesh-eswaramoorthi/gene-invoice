import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

abstract class TokenStorage {
  static TokenStorage create() => kIsWeb ? _WebStorage() : _SecureStorage();

  Future<void> save(String token);
  Future<String?> read();
  Future<void> clear();
}

class _SecureStorage implements TokenStorage {
  static const _key = 'gene_invoice_token';
  final _storage = const FlutterSecureStorage();

  @override Future<void> save(String token) => _storage.write(key: _key, value: token);
  @override Future<String?> read() => _storage.read(key: _key);
  @override Future<void> clear() => _storage.delete(key: _key);
}

class _WebStorage implements TokenStorage {
  static const _key = 'gene_invoice_token';

  @override Future<void> save(String token) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, token);
  }
  @override Future<String?> read() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_key);
  }
  @override Future<void> clear() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_key);
  }
}
