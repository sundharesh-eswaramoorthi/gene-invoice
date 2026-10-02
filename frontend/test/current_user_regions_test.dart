import 'package:flutter_test/flutter_test.dart';
import 'package:gene_invoice/shared/models/auth_models.dart';
import 'package:gene_invoice/shared/models/privileges.dart';

// The /me and login body as the API contract gives it (§6.2): the union of privileges on the user,
// and per region the regional privileges effective there.
Map<String, dynamic> _rita() => {
      'id': 7,
      'username': 'rita',
      'fullName': 'Rita',
      'role': 'CASHIER',
      'customerId': null,
      'privileges': [
        Privileges.customerView,
        Privileges.customerManage,
        Privileges.invoiceView,
        Privileges.invoiceManage,
        Privileges.scopeOverride,
        Privileges.automationManage,
      ],
      'regions': [
        {
          'id': 1,
          'code': 'NORTH',
          'name': 'North',
          'active': true,
          'defaultRegion': true,
          'level': 'READ',
          'scope': 'ALL',
          'privileges': [Privileges.customerView, Privileges.invoiceView, Privileges.scopeOverride],
        },
        {
          'id': 2,
          'code': 'SOUTH',
          'name': 'South',
          'active': true,
          'defaultRegion': false,
          'level': 'MANAGE',
          'scope': 'BOOK',
          'privileges': [
            Privileges.customerView,
            Privileges.customerManage,
            Privileges.invoiceView,
            Privileges.invoiceManage,
          ],
        },
      ],
    };

void main() {
  group('parsing', () {
    test('reads every region with its grant and privileges', () {
      final user = CurrentUser.fromJson(_rita());

      expect(user.regions.map((r) => r.id), [1, 2]);
      final south = user.regions[1];
      expect(south.code, 'SOUTH');
      expect(south.name, 'South');
      expect(south.active, isTrue);
      expect(south.defaultRegion, isFalse);
      expect(south.level, 'MANAGE');
      expect(south.scope, 'BOOK');
      expect(south.privileges, {
        Privileges.customerView,
        Privileges.customerManage,
        Privileges.invoiceView,
        Privileges.invoiceManage,
      });
      expect(user.regions[0].defaultRegion, isTrue);
      // The flat set stays the union, so every existing nav and route check reads as before.
      expect(user.has(Privileges.invoiceManage), isTrue);
    });

    test('a body without regions — an older server, or a customer login — gives none', () {
      final json = _rita()..remove('regions');
      final user = CurrentUser.fromJson(json);

      expect(user.regions, isEmpty);
      expect(user.multiRegion, isFalse);
      expect(CurrentUser.fromJson({..._rita(), 'regions': null}).regions, isEmpty);
    });

    test('tolerates a region missing its optional fields', () {
      final user = CurrentUser.fromJson({
        ..._rita(),
        'regions': [
          {'id': 3, 'code': 'WEST'},
        ],
      });

      final west = user.regions.single;
      expect(west.id, 3);
      expect(west.name, 'WEST');
      expect(west.active, isTrue);
      expect(west.defaultRegion, isFalse);
      expect(west.level, isNull);
      expect(west.scope, isNull);
      expect(west.privileges, isEmpty);
    });

    test('the login result carries the regions too', () {
      final result = LoginResult.fromJson({'token': 't', 'expiresInMs': 1000, 'user': _rita()});

      expect(result.user.regions.map((r) => r.code), ['NORTH', 'SOUTH']);
    });

    test('a user built in code still needs no regions', () {
      const user = CurrentUser(
        id: 1,
        username: 'a',
        fullName: null,
        role: 'ADMIN',
        privileges: {Privileges.invoiceManage},
        customerId: null,
      );

      expect(user.regions, isEmpty);
      expect(user.hasIn(Privileges.invoiceManage, 5), isTrue);
    });
  });

  group('hasIn', () {
    final user = CurrentUser.fromJson(_rita());

    test('answers from that region\'s privileges', () {
      expect(user.hasIn(Privileges.invoiceManage, 2), isTrue);
      expect(user.hasIn(Privileges.invoiceManage, 1), isFalse);
      expect(user.hasIn(Privileges.invoiceView, 1), isTrue);
    });

    test('a region the user holds no grant in allows nothing', () {
      expect(user.hasIn(Privileges.invoiceView, 99), isFalse);
      expect(user.hasIn(Privileges.automationManage, 99), isFalse);
    });

    test('a global privilege is the role\'s alone, so it holds in every region the user sees', () {
      // regions[].privileges never lists a global one (§6.2), yet the role alone grants it (§4.2).
      expect(user.hasIn(Privileges.automationManage, 1), isTrue);
      expect(user.hasIn(Privileges.automationManage, 2), isTrue);
      expect(user.hasIn(Privileges.automationAdmin, 1), isFalse);
      expect(user.regionsWith(Privileges.automationManage), {1, 2});
      expect(user.regionsWith(Privileges.exportData), isEmpty);
    });

    test('falls back to the union when no region is given', () {
      expect(user.hasIn(Privileges.invoiceManage, null), isTrue);
      expect(user.hasIn(Privileges.paymentManage, null), isFalse);
    });

    test('falls back to the union when no regions are known', () {
      final customer = CurrentUser.fromJson({
        ..._rita(),
        'role': 'CUSTOMER',
        'customerId': 12,
        'regions': const [],
      });

      expect(customer.hasIn(Privileges.invoiceView, 2), isTrue);
      expect(customer.hasIn(Privileges.paymentView, 2), isFalse);
    });
  });

  test('regionsWith lists the regions where a privilege holds', () {
    final user = CurrentUser.fromJson(_rita());

    expect(user.regionsWith(Privileges.customerManage), {2});
    expect(user.regionsWith(Privileges.customerView), {1, 2});
    expect(user.regionsWith(Privileges.scopeOverride), {1});
    expect(user.regionsWith(Privileges.paymentManage), isEmpty);
  });

  test('multiRegion needs more than one region', () {
    expect(CurrentUser.fromJson(_rita()).multiRegion, isTrue);

    final one = _rita();
    one['regions'] = [(one['regions'] as List).first];
    expect(CurrentUser.fromJson(one).multiRegion, isFalse);
  });
}
