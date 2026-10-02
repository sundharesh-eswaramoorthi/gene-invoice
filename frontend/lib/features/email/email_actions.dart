import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/scope/view_scope.dart';
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
export 'gmail_connection_screen.dart' show UserGmailStatus;

// Sending is a write, and a past date is read only (D2).
final canSendEmailProvider = Provider<bool>((ref) =>
    !ref.watch(isHistoricalProvider) &&
    (ref.watch(currentUserProvider)?.has(Privileges.emailSend) ?? false));
/// Sending about one record needs EMAIL_SEND in that record's region (§7.5); the server checks it
/// again. With no region known — a snapshot, a customer login — the flat privilege answers.
final canSendEmailInProvider = Provider.family<bool, int?>((ref, regionId) =>
    ref.watch(canSendEmailProvider) &&
    (regionId == null ||
        (ref.watch(currentUserProvider)?.hasIn(Privileges.emailSend, regionId) ?? false)));
final canViewEmailProvider = Provider<bool>(
    (ref) => ref.watch(currentUserProvider)?.has(Privileges.emailView) ?? false);

Future<EmailComposeOutcome> showSendEmailDialog(BuildContext context,
        {required EmailEntityType type,
        required int entityId,
        String? entityLabel,
        EmailEvent? event}) =>
    openSendEmailDialog(context,
        type: type, entityId: entityId, entityLabel: entityLabel, event: event);

Future<EmailComposeOutcome> showSendEmailForPickedRecord(BuildContext context,
        {required EmailEntityType type}) =>
    openSendEmailForPickedRecord(context, type: type);

Widget sendEmailRowAction(BuildContext context,
    {required EmailEntityType type, required int entityId, String? entityLabel, int? regionId}) {
  final canSend =
      ProviderScope.containerOf(context, listen: false).read(canSendEmailInProvider(regionId));
  if (!canSend) return const SizedBox.shrink();
  return IconButton(
    tooltip: 'Send email',
    icon: const Icon(Icons.mail_outline, size: 18),
    onPressed: () =>
        showSendEmailDialog(context, type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

Widget? sendEmailPageAction(BuildContext context, WidgetRef ref, {required EmailEntityType type}) {
  if (!ref.watch(canSendEmailProvider)) return null;
  return OutlinedButton.icon(
    icon: const Icon(Icons.mail_outline, size: 18),
    label: const Text('Send email'),
    onPressed: () => showSendEmailForPickedRecord(context, type: type),
  );
}

BulkActionSpec sendEmailBulkAction(EmailEntityType type) => BulkActionSpec(
      action: 'SEND_EMAIL',
      label: 'Send email',
      icon: Icons.mail_outline,
      endpoint: '/api/emails/bulk',
      // Delivery carries on in the background after the table hears back (E9).
      successMessage: (n) => 'Email queued for $n record${n == 1 ? '' : 's'}',
      buildParams: (context) => openBulkEmailParams(context, type: type),
    );

Widget? sendEmailHeaderButton(BuildContext context, WidgetRef ref,
    {required EmailEntityType type, required int entityId, String? entityLabel, int? regionId}) {
  if (!ref.watch(canSendEmailInProvider(regionId))) return null;
  return OutlinedButton.icon(
    icon: const Icon(Icons.mail_outline, size: 18),
    label: const Text('Send email'),
    onPressed: () =>
        showSendEmailDialog(context, type: type, entityId: entityId, entityLabel: entityLabel),
  );
}

DetailTab? emailDetailTab(WidgetRef ref,
    {required EmailEntityType type, required int entityId, String? entityLabel, int? regionId}) {
  if (!ref.watch(canViewEmailProvider)) return null;
  return DetailTab(
    slug: 'email',
    label: 'Email',
    icon: Icons.mail_outline,
    builder: (context) => EmailTab(
        type: type, entityId: entityId, entityLabel: entityLabel, regionId: regionId),
  );
}

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
    return EmailComposeOutcome.closed;
  }
}
