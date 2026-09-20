import 'package:dio/dio.dart';

/// An answer other than 200: a route returns one to fail its request with [status] and [data],
/// as the server's `ApiError` does.
class FakeFailure {
  final int status;
  final Object? data;
  const FakeFailure(this.status, this.data);
}

/// A fake server behind a real [Dio]: each "METHOD /path" answers with what its route returns (a
/// [FakeFailure] fails it), an unknown one with 404, and every request is kept so a test can
/// read what was sent. A request in [held] is answered only once its future completes.
class FakeBackend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  final Map<String, Future<void>> held = {};
  FakeBackend(this.routes);

  /// The requests made to "METHOD /path", in order.
  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => Dio()
    ..interceptors.add(InterceptorsWrapper(onRequest: (options, handler) async {
      requests.add(options);
      final key = '${options.method} ${options.path}';
      final gate = held[key];
      if (gate != null) await gate;
      final route = routes[key];
      final answer = route == null
          ? const FakeFailure(404, {'message': 'no fake route'})
          : route(options);
      if (answer is FakeFailure) {
        handler.reject(DioException(
          requestOptions: options,
          type: DioExceptionType.badResponse,
          response: Response(requestOptions: options, statusCode: answer.status, data: answer.data),
        ));
        return;
      }
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: answer));
    }));
}
