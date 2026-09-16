import 'package:flutter/material.dart';

import '../../core/format.dart';
import '../../shared/models/email.dart';

/// Where an email's customer or invoice is named beside its subject.
enum EmailAbout {
  /// Always, with the customer's name: the Inbox, which holds emails about anything.
  shown,

  /// Only an invoice's emails, by number: a customer's tab, which also lists its invoices' emails.
  invoicesOnly,

  /// Never: an invoice's own tab, where it would repeat the page's title.
  hidden,
}

/// Everything about one email: what it is about, From, To, Subject, Body, who sent it and when.
/// Shared by the Email tab and an email opened from the Inbox.
class EmailDetails extends StatelessWidget {
  final EmailMessage email;
  final EmailAbout about;

  /// Called with the email when its customer or invoice label is tapped.
  final void Function(EmailMessage email)? onOpenAbout;

  const EmailDetails({
    super.key,
    required this.email,
    this.about = EmailAbout.shown,
    this.onOpenAbout,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SelectableText(email.subject,
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            if (about == EmailAbout.shown ||
                (about == EmailAbout.invoicesOnly && email.invoiceNumber != null))
              EmailAboutChip(
                email: email,
                withCustomer: about == EmailAbout.shown,
                onTap: onOpenAbout,
              ),
          ],
        ),
        const SizedBox(height: 8),
        _line(context, 'From', _sender(email.from)),
        _line(context, 'To', _recipients(context, email)),
        _line(context, 'Sent by', Text(email.sentByName)),
        _line(context, 'Sent', Text(formatDateTime(email.sentAt))),
        const Divider(height: 20),
        email.body.isEmpty
            ? Text('No message', style: muted?.copyWith(fontStyle: FontStyle.italic))
            : SelectableText(email.body),
      ],
    );
  }

  static Widget _line(BuildContext context, String label, Widget value) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 64,
              child: Text(label, style: Theme.of(context).textTheme.labelMedium),
            ),
            Expanded(child: value),
          ],
        ),
      );

  static Widget _sender(EmailSender from) {
    if (from.type == EmailPartyType.ROLE) {
      return Text('${from.name} (role) — ${from.address ?? 'no email address'}');
    }
    return Text(from.address == null ? from.name : '${from.name} <${from.address}>');
  }

  static Widget _recipients(BuildContext context, EmailMessage email) {
    final muted = Theme.of(context)
        .textTheme
        .bodySmall
        ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    final users = email.users;
    final roles = email.roles;
    final addresses = email.customerAddresses;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (users.isNotEmpty)
          Text(users.map((u) => u.address == null ? u.name! : '${u.name} <${u.address}>').join(', ')),
        for (final r in roles)
          Text.rich(TextSpan(children: [
            TextSpan(text: '${r.name} (role)${r.address == null ? '' : ' <${r.address}>'}: '),
            r.members.isEmpty
                ? TextSpan(text: 'nobody was in it', style: muted?.copyWith(fontStyle: FontStyle.italic))
                : TextSpan(text: r.members.map((m) => m.name).join(', ')),
          ])),
        if (addresses.isNotEmpty)
          Text('Customer: ${addresses.map((a) => a.address).join(', ')}'),
      ],
    );
  }
}

/// Names the record an email is about: its invoice number, or its customer.
class EmailAboutChip extends StatelessWidget {
  final EmailMessage email;

  /// Adds the customer's name to an invoice's number.
  final bool withCustomer;
  final void Function(EmailMessage email)? onTap;

  const EmailAboutChip({super.key, required this.email, this.withCustomer = true, this.onTap});

  @override
  Widget build(BuildContext context) {
    final isInvoice = email.invoiceNumber != null;
    final chip = Chip(
      visualDensity: VisualDensity.compact,
      avatar: Icon(isInvoice ? Icons.receipt_long_outlined : Icons.people_outline, size: 16),
      label: Text(!isInvoice
          ? email.customerName
          : 'Invoice ${email.invoiceNumber}${withCustomer ? ' • ${email.customerName}' : ''}'),
    );
    if (onTap == null) return chip;
    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () => onTap!(email),
      child: chip,
    );
  }
}
