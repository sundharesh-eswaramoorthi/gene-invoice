/// Maximum lengths of text fields, mirroring the backend's column limits
/// (backend common/FieldLimits.java), so a field stops taking input where the server would refuse it.
/// A limit the form only warns about, rather than enforces, says so.
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

  /// Days between an invoice's date and its due date beyond which the form asks whether that is
  /// really meant. The API accepts it either way (app.invoice.due-date-horizon-days, §2.2).
  static const dueDateHorizonDays = 365;

  // Documents (§4.1, §4.3).
  static const documentDescription = 500;

  /// The width of the stored filename. Nobody types this — it is whatever the file was called —
  /// so the server cuts a longer one to fit rather than refusing the upload (AC-C8); it is
  /// mirrored here so a name shown back is never wider than the one that was kept.
  static const documentFilename = 260;

  /// The largest file the server keeps (app.documents.max-size-bytes). The form refuses a bigger
  /// one before sending it, in the server's own words (AC-C6, AC-C20).
  static const documentMaxBytes = 10485760;

  // A Gmail connection's three values, as the mail service takes them (mail-service.md §4.3).
  static const gmailClientId = 300;
  static const gmailClientSecret = 300;
  static const gmailRefreshToken = 2000;
}
