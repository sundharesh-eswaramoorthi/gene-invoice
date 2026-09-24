import 'package:dio/dio.dart';
import 'package:gene_invoice/core/api/api_client.dart';

class FakeFailure {
  final int status;
  final Object? data;
  const FakeFailure(this.status, this.data);
}

/// A save the fake server HELD for approval: 202 with the Accepted body, not 200 with a record.
///
/// It exists so a widget test can drive the real maker-checker path. Dio does not throw on a 2xx,
/// so a route that answered this with plain `resolve` would look like a success to every caller —
/// which is exactly the defect pendingApprovalOnResponse exists to close (B2).
class FakeAccepted {
  final Object? data;
  const FakeAccepted(this.data);
}

class FakeBackend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  final Map<String, Future<void>> held = {};
  FakeBackend(this.routes);

  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => Dio()
    ..interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) async {
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
            response:
                Response(requestOptions: options, statusCode: answer.status, data: answer.data),
          ));
          return;
        }
        if (answer is FakeAccepted) {
          // The `true` is the whole point and is not decoration: RequestInterceptorHandler.resolve
          // takes [bool callFollowingResponseInterceptor = false], so without it the 202 skips
          // every response interceptor, pendingApprovalOnResponse never runs, and the test passes
          // for the wrong reason (B2).
          handler.resolve(
            Response(requestOptions: options, statusCode: 202, data: answer.data),
            true,
          );
          return;
        }
        handler.resolve(Response(requestOptions: options, statusCode: 200, data: answer));
      },
      // Production code, by reference, rather than a copy of it here: a test that exercised a
      // second implementation of the 202 rule would prove nothing about the app (B2).
      onResponse: pendingApprovalOnResponse,
    ));
}
