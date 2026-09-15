import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';

DioException _error(Map<String, dynamic> body) {
  final options = RequestOptions(path: '/api/things');
  return DioException(
    requestOptions: options,
    response: Response(requestOptions: options, statusCode: 400, data: body),
  );
}

void main() {
  group('apiErrorMessage', () {
    test('names each invalid field instead of the generic message', () {
      final message = apiErrorMessage(_error({
        'message': 'One or more fields are invalid',
        'fieldErrors': {'notes': 'must be at most 500 characters', 'collectionPocUserId': 'must be a number'},
      }));
      expect(message, contains('Notes must be at most 500 characters'));
      expect(message, contains('Collection poc user id must be a number'));
      expect(message, isNot(contains('One or more fields')));
    });

    test('falls back to the server message', () {
      expect(apiErrorMessage(_error({'message': 'Email already exists'})), 'Email already exists');
    });
  });

  group('parseMoneyInput', () {
    test('accepts whole amounts and up to two decimals', () {
      expect(parseMoneyInput('10'), 10);
      expect(parseMoneyInput(' 10.5 '), 10.5);
      expect(parseMoneyInput('10.55'), 10.55);
    });

    test('refuses anything the backend would refuse', () {
      for (final bad in ['10.555', '0.001', 'abc', '', '-5', '1e3', '10.']) {
        expect(parseMoneyInput(bad), isNull, reason: bad);
      }
    });
  });
}
