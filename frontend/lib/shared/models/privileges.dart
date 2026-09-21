class Privileges {
  Privileges._();

  static const userView = 'USER_VIEW';
  static const userManage = 'USER_MANAGE';

  static const roleView = 'ROLE_VIEW';
  static const roleManage = 'ROLE_MANAGE';

  static const customerView = 'CUSTOMER_VIEW';
  static const customerManage = 'CUSTOMER_MANAGE';

  static const productView = 'PRODUCT_VIEW';
  static const productManage = 'PRODUCT_MANAGE';

  static const invoiceView = 'INVOICE_VIEW';
  static const invoiceManage = 'INVOICE_MANAGE';

  static const paymentView = 'PAYMENT_VIEW';
  static const paymentManage = 'PAYMENT_MANAGE';

  static const disputeCreate = 'DISPUTE_CREATE';
  static const disputeView = 'DISPUTE_VIEW';
  static const disputeManage = 'DISPUTE_MANAGE';

  static const notificationView = 'NOTIFICATION_VIEW';

  static const auditView = 'AUDIT_VIEW';

  static const pocView = 'POC_VIEW';
  static const pocAssign = 'POC_ASSIGN';
  static const pocAssignableSales = 'POC_ASSIGNABLE_SALES';
  static const pocAssignableSuccess = 'POC_ASSIGNABLE_SUCCESS';
  static const pocAssignableCollection = 'POC_ASSIGNABLE_COLLECTION';
  static const scopeOverride = 'SCOPE_OVERRIDE';

  static const promiseView = 'PROMISE_VIEW';
  static const promiseManage = 'PROMISE_MANAGE';
  static const promiseOverride = 'PROMISE_OVERRIDE';

  static const exportData = 'EXPORT_DATA';

  static const emailView = 'EMAIL_VIEW';
  static const emailSend = 'EMAIL_SEND';

  static const documentView = 'DOCUMENT_VIEW';
  static const documentManage = 'DOCUMENT_MANAGE';
}
