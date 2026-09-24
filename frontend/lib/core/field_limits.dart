abstract final class FieldLimits {
  static const username = 80;
  static const email = 120;
  static const fullName = 120;
  static const roleName = 80;
  static const roleDescription = 255;
  static const invoiceNotes = 500;
  static const paymentNotes = 300;
  static const paymentMethod = 40;
  static const promiseNotes = 1000;
  static const emailSubject = 500;
  static const emailBody = 20000;

  static const dueDateHorizonDays = 365;

  static const documentDescription = 500;

  static const documentFilename = 260;

  static const documentMaxBytes = 10485760;

  /// The server's FieldLimits.TASK_TITLE / TASK_NOTES. Both are plain varchar columns and a save
  /// longer than these is a 400, so the boxes stop at the same number rather than letting somebody
  /// type a paragraph the server will refuse (A6).
  static const taskTitle = 200;
  static const taskNotes = 2000;

  static const gmailClientId = 300;
  static const gmailClientSecret = 300;
  static const gmailRefreshToken = 2000;
}
