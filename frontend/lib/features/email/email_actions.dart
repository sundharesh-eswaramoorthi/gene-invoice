import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/table/data_table_scaffold.dart';
import '../../shared/models/privileges.dart';
import '../../shared/widgets/detail_scaffold.dart';
import '../auth/auth_controller.dart';
import 'email_entity.dart';
import 'email_models.dart';
import 'email_tab.dart';
import 'send_email_dialog.dart';

export 'email_entity.dart' show EmailEntityType;
export 'email_models.dart' show EmailEvent;
export 'send_email_dialog.dart' show EmailComposeOutcome;
// The user details page's "Gmail" line.
export 'gmail_connection_screen.dart' show UserGmailStatus;

// The email feature as the rest of the app sees it. Screens import only this file, so the
// dialog, the tab and their providers can change without touching every list and details page.

/// True when the signed-in user holds EMAIL_SEND / EMAIL_VIEW.
final canSendEmailProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.emailSend) ?? false);
final canViewEmailProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.emailView) ?? false);

/// Compose an email about one record. Resolves [EmailComposeOutcome.sent] when an email was saved
/// (sent or not).
Future<EmailComposeOutcome> showSendEmailDialog(BuildContext context,
        {required EmailEntityType type,
        required int entityId,
        String? entityLabel,
        EmailEvent? event}) =>
    openSendEmailDialog(context,
        type: type, entityId: entityId, entityLabel: entityLabel, event: event);

/// Compose from a list page: the dialog first asks which record the email is about.
Future<EmailComposeOutcome> showSendEmailForPickedRecord(BuildContext context,
        {required EmailEntityType type}) =>
    openSendEmailForPickedRecord(context, type: type);

/// Row quick action: an IconButton (Icons.mail_outline, size 18, tooltip 'Send email'), or an empty
/// SizedBox when the user cannot send.
Widget sendEmailRowAction(BuildContext context,
    {required EmailEntityType type, required int entityId, String? entityLabel}) {
  // Row actions are built without a WidgetRef; the list screen already rebuilds when the
  // signed-in user changes, so a read is enough here.
  final canSend = ProviderScope.containerOf(context, listen: false).read(canSendEmailProvider);
  if (!canSend) return const SizedBox.shrink();
  return IconButton(
    tooltip: 'Send email',
    icon: const Icon(Icons.mail_outline, size: 18),
    onPressed: () =>
        showSendEmailDialog(context, type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

/// List page action for DataTableScaffold.actions: OutlinedButton.icon 'Send email'. Returns null when
/// the user cannot send, so callers write `if (a != null) a`.
Widget? sendEmailPageAction(BuildContext context, WidgetRef ref, {required EmailEntityType type}) {
  if (!ref.watch(canSendEmailProvider)) return null;
  return OutlinedButton.icon(
    icon: const Icon(Icons.mail_outline, size: 18),
    label: const Text('Send email'),
    onPressed: () => showSendEmailForPickedRecord(context, type: type),
  );
}

/// Bulk action for DataTableScaffold.bulkActions ('Send email', posts to /api/emails/bulk with
/// params from the compose dialog in bulk mode). Callers add it only when canSendEmailProvider is true.
BulkActionSpec sendEmailBulkAction(EmailEntityType type) => BulkActionSpec(
      action: 'SEND_EMAIL',
      label: 'Send email',
      icon: Icons.mail_outline,
      endpoint: '/api/emails/bulk',
      // Delivery carries on in the background after the table hears back (E9).
      successMessage: (n) => 'Email queued for $n record${n == 1 ? '' : 's'}',
      buildParams: (context) => openBulkEmailParams(context, type: type),
    );

/// Detail page header button (for DetailScaffold.titleTrailing): OutlinedButton.icon 'Send email', or null.
Widget? sendEmailHeaderButton(BuildContext context, WidgetRef ref,
    {required EmailEntityType type, required int entityId, String? entityLabel}) {
  if (!ref.watch(canSendEmailProvider)) return null;
  return OutlinedButton.icon(
    icon: const Icon(Icons.mail_outline, size: 18),
    label: const Text('Send email'),
    onPressed: () =>
        showSendEmailDialog(context, type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

/// The Email tab for DetailScaffold.tabs (slug 'email', label 'Email', Icons.mail_outline). Null when
/// the user lacks EMAIL_VIEW, so callers write `if (tab != null) tab`.
DetailTab? emailDetailTab(WidgetRef ref,
    {required EmailEntityType type, required int entityId, String? entityLabel}) {
  if (!ref.watch(canViewEmailProvider)) return null;
  return DetailTab(
    slug: 'email',
    label: 'Email',
    icon: Icons.mail_outline,
    builder: (context) => EmailTab(type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

/// The "Notify through email" checkbox (CheckboxListTile). Renders SizedBox.shrink() without EMAIL_SEND.
class NotifyByEmailCheckbox extends ConsumerWidget {
  const NotifyByEmailCheckbox({super.key, required this.value, required this.onChanged});
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!ref.watch(canSendEmailProvider)) return const SizedBox.shrink();
    return CheckboxListTile(
      value: value,
      onChanged: (v) => onChanged(v ?? false),
      title: const Text('Notify through email'),
      subtitle: const Text('Write an email about it after saving'),
      controlAffinity: ListTileControlAffinity.leading,
      contentPadding: EdgeInsets.zero,
      dense: true,
    );
  }
}

/// Call after a successful save, with a context that is still mounted (the screen's, never a closed
/// dialog's). When [notify] is true and the user may send, opens the compose dialog pre-filled for the
/// record and event, and resolves with how it ended ([EmailComposeOutcome.closed] when none
/// opened). A caller that moves on afterwards (`context.go`) must not when it resolves
/// [EmailComposeOutcome.leftForGmail]: the app is already on its way to the Gmail connection page.
/// Never throws.
Future<EmailComposeOutcome> notifyByEmailAfterSave(BuildContext context,
    {required bool notify,
    required EmailEntityType type,
    required int entityId,
    required EmailEvent event}) async {
  if (!notify || !context.mounted) return EmailComposeOutcome.closed;
  try {
    final canSend = ProviderScope.containerOf(context, listen: false).read(canSendEmailProvider);
    if (!canSend) return EmailComposeOutcome.closed;
    return await showSendEmailDialog(context, type: type, entityId: entityId, event: event);
  } catch (_) {
    // The record is already saved; a compose form that could not open must not undo that
    // success in the caller's flow.
    return EmailComposeOutcome.closed;
  }
}
