import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/scope/view_scope.dart';
import '../../core/table/table_providers.dart';
import '../../shared/models/auth_models.dart';

class AuthState {
  final bool loading;
  final CurrentUser? user;
  final String? error;

  const AuthState({this.loading = false, this.user, this.error});

  bool get isAuthenticated => user != null;

  AuthState copyWith({bool? loading, CurrentUser? user, String? error, bool clearUser = false}) =>
      AuthState(
        loading: loading ?? this.loading,
        user: clearUser ? null : (user ?? this.user),
        error: error,
      );
}

class AuthController extends StateNotifier<AuthState> {
  AuthController(this._ref) : super(const AuthState());

  final Ref _ref;

  Future<void> bootstrap() async {
    final storage = _ref.read(tokenStorageProvider);
    final token = await storage.read();
    if (token == null) return;
    state = state.copyWith(loading: true);
    try {
      final dio = _ref.read(dioProvider);
      final res = await dio.get('/api/auth/me');
      final user = CurrentUser.fromJson(res.data as Map<String, dynamic>);
      state = AuthState(user: user);
    } catch (_) {
      await storage.clear();
      state = const AuthState();
    }
  }

  /// Reads `/me` again without signing anyone out, after a change to the user's own regions — a
  /// region created (its creator is granted it) or renamed. A failure keeps what is there.
  Future<void> refreshUser() async {
    if (state.user == null) return;
    try {
      final res = await _ref.read(dioProvider).get('/api/auth/me');
      final user = CurrentUser.fromJson(res.data as Map<String, dynamic>);
      if (state.user?.id == user.id) state = AuthState(user: user);
    } catch (_) {
      // The server stays authoritative; a stale list of regions only offers fewer choices.
    }
  }

  Future<bool> login(String username, String password) async {
    state = state.copyWith(loading: true, error: null);
    try {
      final dio = _ref.read(dioProvider);
      final res = await dio.post('/api/auth/login', data: {
        'username': username,
        'password': password,
      });
      final result = LoginResult.fromJson(res.data as Map<String, dynamic>);
      await _ref.read(tokenStorageProvider).save(result.token);
      state = AuthState(user: result.user);
      return true;
    } catch (e) {
      state = AuthState(error: apiErrorMessage(e));
      return false;
    }
  }

  Future<void> logout() async {
    await _ref.read(tokenStorageProvider).clear();
    // The next person at this browser should get their own table defaults, not this one's (D-57).
    await _ref.read(pageSizeStoreProvider.notifier).clear();
    _ref.read(viewScopeProvider.notifier).clear();
    state = const AuthState();
  }
}

final authControllerProvider = StateNotifierProvider<AuthController, AuthState>((ref) {
  return AuthController(ref);
});

final currentUserProvider = Provider<CurrentUser?>((ref) =>
    ref.watch(authControllerProvider).user);
