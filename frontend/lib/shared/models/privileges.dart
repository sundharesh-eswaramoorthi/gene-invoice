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

  static const regionView = 'REGION_VIEW';
  static const regionManage = 'REGION_MANAGE';

  static const taskView = 'TASK_VIEW';
  static const taskManage = 'TASK_MANAGE';

  static const automationView = 'AUTOMATION_VIEW';
  static const automationManage = 'AUTOMATION_MANAGE';
  static const automationAdmin = 'AUTOMATION_ADMIN';

  static const changeApprove = 'CHANGE_APPROVE';
  static const approvalThresholdManage = 'APPROVAL_THRESHOLD_MANAGE';

  /// The privileges the role alone decides, whatever the region — the server's `Privileges.GLOBAL`
  /// (§4.1). `/me.regions[].privileges` lists only the others.
  static const global = {
    userView, userManage, roleView, roleManage, productView, productManage, notificationView,
    exportData, regionView, regionManage, automationView, automationManage, automationAdmin,
  };

  /// The regional privileges a MANAGE grant is needed for, and those needing APPROVE (§4.1); every
  /// other regional one needs READ. Only for pre-filling a new user's access to what their role
  /// uses — the server decides every right itself.
  static const manageLevel = {
    customerManage, invoiceManage, paymentManage, promiseManage, promiseOverride, disputeManage,
    disputeCreate, pocAssign, emailSend, documentManage, taskManage,
  };
  static const approveLevel = {changeApprove, approvalThresholdManage};

  /// READ, MANAGE or APPROVE for a regional privilege; null for a global one.
  static String? levelFor(String privilege) {
    if (global.contains(privilege)) return null;
    if (approveLevel.contains(privilege)) return 'APPROVE';
    if (manageLevel.contains(privilege)) return 'MANAGE';
    return 'READ';
  }
}
