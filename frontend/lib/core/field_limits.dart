/// Maximum lengths of text fields, mirroring the backend's column limits
/// (backend common/FieldLimits.java), so a field stops taking input where the server would refuse it.
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
}
