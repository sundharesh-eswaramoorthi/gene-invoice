import 'package:dio/dio.dart';

class FakeFailure {
  final int status;
  final Object? data;
  const FakeFailure(this.status, this.data);
}

/// An answer with a status other than 200, or with headers — a 202 held for approval, say, or a
/// list carrying `X-View-As-Of`. A 2xx goes down Dio's success path like any other answer.
class FakeReply {
  final int status;
  final Object? data;
  final Map<String, List<String>> headers;
  const FakeReply(this.status, this.data, {this.headers = const {}});
}

class FakeBackend {
  final Map<String, Object? Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];
  final Map<String, Future<void>> held = {};
  FakeBackend(this.routes);

  List<RequestOptions> sent(String key) =>
      requests.where((r) => '${r.method} ${r.path}' == key).toList();

  Dio get dio => dioWith(const []);

  /// One Dio with [before] running ahead of the fake routing, so what they add to a request (the
  /// view scope's parameters, say) is what [sent] records. Build it once per test: every call makes
  /// a new instance.
  Dio dioWith(List<Interceptor> before) => Dio()
    ..interceptors.addAll(before)
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
      if (answer is FakeReply) {
        final response = Response(
          requestOptions: options,
          statusCode: answer.status,
          data: answer.data,
          headers: Headers.fromMap(answer.headers),
        );
        if (answer.status >= 400) {
          handler.reject(DioException(
            requestOptions: options,
            type: DioExceptionType.badResponse,
            response: response,
          ));
        } else {
          handler.resolve(response);
        }
        return;
      }
      handler.resolve(Response(requestOptions: options, statusCode: 200, data: answer));
    }));
}

const _operators = {
  'TEXT': ['contains', 'eq', 'neq', 'isEmpty', 'isNotEmpty'],
  'ENUM': ['eq', 'neq', 'in', 'notIn'],
  'BOOLEAN': ['eq'],
  'NUMBER': ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'between'],
  'MONEY': ['eq', 'neq', 'gt', 'gte', 'lt', 'lte', 'between'],
  'DATE': ['gte', 'lte', 'between', 'relative'],
  'REFERENCE': ['eq', 'neq', 'in', 'isEmpty', 'isNotEmpty'],
};

Map<String, dynamic> _column(String name, String label, String type,
        {bool sortable = true, List<String> enumValues = const [], String? referenceKind}) =>
    {
      'name': name,
      'label': label,
      'type': type,
      'sortable': sortable,
      'filterable': true,
      'operators': _operators[type],
      'enumValues': enumValues,
      'referenceKind': referenceKind,
    };

/// `GET /api/table-schemas/automation-rules` as the server answers it (TableSchemas.AUTOMATION_RULES):
/// status is worked out, so it filters but does not sort, and neither do the owner and regions.
final automationRulesSchema = <String, dynamic>{
  'entity': 'automation-rules',
  'defaultSort': 'name,asc',
  'pageSizes': const [10, 20, 50],
  'columns': [
    _column('id', 'Id', 'NUMBER'),
    _column('name', 'Rule', 'TEXT'),
    _column('enabled', 'On', 'BOOLEAN'),
    _column('status', 'Status', 'ENUM',
        sortable: false, enumValues: const ['ACTIVE', 'DISABLED', 'SUSPENDED']),
    _column('suspendedReason', 'Why suspended', 'ENUM', enumValues: const ['OWNER_LACKS_RIGHTS']),
    _column('triggerKind', 'Starts', 'ENUM',
        enumValues: const ['CREATED', 'UPDATED', 'SCHEDULE_DAILY', 'SCHEDULE_WEEKLY']),
    _column('entityType', 'On records', 'ENUM',
        enumValues: const ['CUSTOMER', 'INVOICE', 'PAYMENT']),
    _column('ownerUserId', 'Runs as', 'REFERENCE', sortable: false, referenceKind: 'staffUser'),
    _column('regionIds', 'Regions', 'REFERENCE', sortable: false, referenceKind: 'region'),
    _column('lastRunStatus', 'Last run', 'ENUM',
        enumValues: const ['QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED']),
    _column('lastRunAt', 'Last run at', 'DATE'),
    _column('nextRunAt', 'Next run', 'DATE'),
    _column('createdAt', 'Created', 'DATE'),
    _column('updatedAt', 'Updated', 'DATE'),
  ],
};

/// `GET /api/table-schemas/approvals` as the server answers it (TableSchemas.APPROVALS): who asked
/// filters, but does not sort.
final approvalsSchema = <String, dynamic>{
  'entity': 'approvals',
  'defaultSort': 'requestedAt,desc',
  'pageSizes': const [10, 20, 50],
  'columns': [
    _column('id', 'Id', 'NUMBER'),
    _column('status', 'Status', 'ENUM',
        enumValues: const ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'FAILED']),
    _column('operation', 'Change', 'ENUM'),
    _column('entityType', 'On', 'ENUM'),
    _column('entityId', 'Record id', 'NUMBER'),
    _column('entityLabel', 'Record', 'TEXT'),
    _column('customerId', 'Customer', 'REFERENCE', sortable: false, referenceKind: 'customer'),
    _column('customerName', 'Customer name', 'TEXT'),
    _column('regionId', 'Region', 'REFERENCE', referenceKind: 'region'),
    _column('amount', 'Amount', 'MONEY'),
    _column('threshold', 'Limit', 'MONEY'),
    _column('summary', 'Summary', 'TEXT', sortable: false),
    _column('requestedByUserId', 'Requested by', 'REFERENCE',
        sortable: false, referenceKind: 'staffUser'),
    _column('origin', 'Made by', 'ENUM', enumValues: const ['USER', 'RULE']),
    _column('requestedAt', 'Requested', 'DATE'),
    _column('decidedAt', 'Decided', 'DATE'),
  ],
};
