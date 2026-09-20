import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/core/api/api_client.dart';
import 'package:gene_invoice/core/format.dart';

DioException _error(Map<String, dynamic> body) => _failure(400, body);

/// The same answer as a request that asked for bytes receives it.
DioException _bytes(String body) =>
    _failure(404, Uint8List.fromList(utf8.encode(body)));

DioException _failure(int status, Object body) {
  final options = RequestOptions(path: '/api/things');
  return DioException(
    requestOptions: options,
    response: Response(requestOptions: options, statusCode: status, data: body),
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

    /// The server's refusals about a document are whole sentences, and the client's own checks
    /// on the identical conditions use the very same wording, so the message must read the same
    /// whichever caught it (AC-C9). Naming the field turned them into "File Files of this kind
    /// cannot be attached (…)" (UI-03).
    test('shows a field message that is already a sentence exactly as the server wrote it', () {
      const refusals = [
        'Files of this kind cannot be attached (PDF, PNG, JPEG, Word or Excel only)',
        'The file is larger than 10 MB',
      ];
      for (final refusal in refusals) {
        expect(apiErrorMessage(_error({'fieldErrors': {'file': refusal}})), refusal);
      }
    });

    test('still names the field on a Bean Validation fragment', () {
      expect(apiErrorMessage(_error({'fieldErrors': {'name': 'must not be blank'}})),
          'Name must not be blank');
      expect(
          apiErrorMessage(_error({
            'fieldErrors': {'items[0].quantity': 'must be greater than 0'}
          })),
          'Items[0] quantity must be greater than 0');
    });

    /// A download asks for bytes, so Dio hands its error body over as bytes too rather than as
    /// the Map every other call gets. The server's own sentence is in there all the same.
    test('reads an error body that came back as bytes', () {
      expect(apiErrorMessage(_bytes('{"message":"Document not found"}')), 'Document not found');
      expect(
          apiErrorMessage(_bytes('{"message":"One or more fields are invalid",'
              '"fieldErrors":{"file":"The file is larger than 10 MB"}}')),
          'The file is larger than 10 MB');
    });

    test('bytes that are not the app\'s error shape are not shown as a message', () {
      expect(apiErrorMessage(_bytes('<html>gateway timeout</html>')),
          isNot(contains('gateway timeout')));
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
