// Area C fixtures: two customers with their own records, and one login per role under test.
const rt = require('../lib.js');

const P = 'c';

async function need(res, what) {
  if (res.status >= 300) throw new Error(`${what} -> ${res.status} ${res.text}`);
  return res.json;
}

/** A role with exactly these privileges, so a single missing privilege can be tested. */
async function createRole(admin, name, privileges) {
  const r = await rt.api('POST', '/api/roles', {
    token: admin, body: { name, description: 'area-c regression', privileges },
  });
  return need(r, `create role ${name}`);
}

async function createUserWithRole(admin, roleId, prefix) {
  const username = rt.uniq(prefix);
  const r = await rt.api('POST', '/api/users', {
    token: admin,
    body: {
      username, email: `${username}@rt.local`, fullName: username.toUpperCase(),
      password: rt.PASSWORD, roleId, active: true,
    },
  });
  await need(r, 'create user');
  return { username, password: rt.PASSWORD, token: await rt.login(username, rt.PASSWORD) };
}

async function build() {
  const admin = await rt.adminToken();
  const me = await rt.api('GET', '/api/auth/me', { token: admin });
  const adminId = me.json?.id ?? me.json?.user?.id ?? 1;

  const product = await need(await rt.api('POST', '/api/products', {
    token: admin, body: { name: rt.uniq(P + '-prod'), description: 'area-c', price: '100.00', active: true },
  }), 'create product');

  async function customerSet(tag) {
    const customer = await rt.createCustomer(admin, `${P}-${tag}`);
    const invoice = await need(await rt.api('POST', '/api/invoices', {
      token: admin,
      body: {
        customerId: customer.id, salesPocUserId: adminId, notes: `area-c ${tag}`,
        items: [{ productId: product.id, quantity: 1, unitPrice: '100.00' }],
      },
    }), 'create invoice');
    const payment = await need(await rt.api('POST', '/api/payments', {
      token: admin,
      body: {
        customerId: customer.id, amount: '10.00', method: 'CASH', notes: `area-c ${tag}`,
        collectionPocUserId: adminId, invoiceIds: [invoice.id],
      },
    }), 'create payment');
    const token = await rt.login(customer.username, customer.password);
    return { customer, invoice, payment, token };
  }

  const A = await customerSet('a');
  const B = await customerSet('b');

  // A sales POC whose book is customer B's invoice only — everything of A's is outside it.
  const salesPoc = await rt.createStaff(admin, 'SALES_POC', `${P}-sales`);
  await need(await rt.api('PATCH', `/api/invoices/${B.invoice.id}`, {
    token: admin, body: { salesPocUserId: salesPoc.id },
  }), 'reseat invoice B sales poc');
  const salesPocToken = await rt.login(salesPoc.username, salesPoc.password);

  // VIEWER: DOCUMENT_VIEW but no DOCUMENT_MANAGE.
  const viewer = await rt.createStaff(admin, 'VIEWER', `${P}-viewer`);
  const viewerToken = await rt.login(viewer.username, viewer.password);

  // DOCUMENT_VIEW + DOCUMENT_MANAGE but no record privileges at all.
  const docOnlyRole = await createRole(admin, rt.uniq(`${P}-docsonly`).toUpperCase(),
    ['DOCUMENT_VIEW', 'DOCUMENT_MANAGE']);
  const docOnly = await createUserWithRole(admin, docOnlyRole.id, `${P}-donly`);

  // Every record privilege but neither document privilege.
  const noDocRole = await createRole(admin, rt.uniq(`${P}-nodoc`).toUpperCase(),
    ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'INVOICE_VIEW', 'INVOICE_MANAGE',
      'PAYMENT_VIEW', 'PAYMENT_MANAGE', 'SCOPE_OVERRIDE']);
  const noDoc = await createUserWithRole(admin, noDocRole.id, `${P}-nodoc`);

  // Can see and manage customers and documents, but cannot see invoices.
  const noInvoiceRole = await createRole(admin, rt.uniq(`${P}-noinv`).toUpperCase(),
    ['CUSTOMER_VIEW', 'CUSTOMER_MANAGE', 'DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'SCOPE_OVERRIDE']);
  const noInvoice = await createUserWithRole(admin, noInvoiceRole.id, `${P}-noinv`);

  // Can see invoices but not manage them, with both document privileges.
  const viewOnlyInvoiceRole = await createRole(admin, rt.uniq(`${P}-invview`).toUpperCase(),
    ['INVOICE_VIEW', 'DOCUMENT_VIEW', 'DOCUMENT_MANAGE', 'SCOPE_OVERRIDE']);
  const invoiceViewOnly = await createUserWithRole(admin, viewOnlyInvoiceRole.id, `${P}-invview`);

  return {
    admin, adminId, product,
    A, B,
    salesPoc: { ...salesPoc, token: salesPocToken },
    viewer: { ...viewer, token: viewerToken },
    docOnly, noDoc, noInvoice, invoiceViewOnly,
  };
}

module.exports = { build };
