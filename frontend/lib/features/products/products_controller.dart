import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../shared/models/product.dart';

/// Immutable state for the products screen search: the last successful
/// product list, the current trimmed search text, whether a request is in
/// flight, and the error of the current query (null after any success).
class ProductSearchState {
  final List<Product> products;
  final String query;
  final bool loading;
  final String? error;

  const ProductSearchState({
    this.products = const [],
    this.query = '',
    this.loading = false,
    this.error,
  });

  ProductSearchState copyWith({
    List<Product>? products,
    String? query,
    bool? loading,
    String? error,
  }) =>
      ProductSearchState(
        products: products ?? this.products,
        query: query ?? this.query,
        loading: loading ?? this.loading,
        error: error,
      );
}

/// Debounced server-side product search.
///
/// Every edit trims the raw text, advances [_generation] and cancels the
/// pending timer, then schedules one request after a 300 ms pause. A
/// completion mutates state only while its captured generation and trimmed
/// text still match the current ones, so out-of-order responses are
/// discarded silently. There is no automatic retry.
class ProductSearchController extends StateNotifier<ProductSearchState> {
  ProductSearchController(this._ref)
      : super(const ProductSearchState(loading: true)) {
    _request('', _generation);
  }

  final Ref _ref;

  Timer? _debounce;
  int _generation = 0;

  static const Duration _delay = Duration(milliseconds: 300);

  /// Called on every change of the search field (no submit action).
  void onSearchChanged(String rawText) {
    final query = rawText.trim();
    final generation = ++_generation;
    _debounce?.cancel();
    state = state.copyWith(query: query);
    _debounce = Timer(_delay, () => _request(query, generation));
  }

  /// Immediately re-requests the currently held trimmed text (pull-to-
  /// refresh and the refresh after saving a product).
  Future<void> refreshCurrent() {
    _debounce?.cancel();
    final generation = ++_generation;
    return _request(state.query, generation);
  }

  Future<void> _request(String query, int generation) async {
    if (!mounted || generation != _generation) return;
    state = state.copyWith(loading: true);
    try {
      final dio = _ref.read(dioProvider);
      final res = await dio.get(
        '/api/products',
        queryParameters: query.isEmpty ? null : {'search': query},
      );
      final products = (res.data as List)
          .cast<Map<String, dynamic>>()
          .map(Product.fromJson)
          .toList();
      if (!mounted || generation != _generation || query != state.query) {
        return;
      }
      state = ProductSearchState(products: products, query: query);
    } catch (e) {
      if (!mounted || generation != _generation || query != state.query) {
        return;
      }
      state = state.copyWith(loading: false, error: apiErrorMessage(e));
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }
}

final productSearchControllerProvider = StateNotifierProvider.autoDispose<
    ProductSearchController,
    ProductSearchState>((ref) => ProductSearchController(ref));
