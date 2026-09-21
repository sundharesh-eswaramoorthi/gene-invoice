import 'package:flutter/foundation.dart';

import '../../features/email/email_models.dart';

/// The write side of an assignee is the very token the email To field already sends —
/// `{"type":"ROLE","role":"COLLECTION_POC","level":"CUSTOMER"}` — so it is not modelled a second
/// time here. [EmailToken], the level constants and the role options a token is picked from are
/// re-exported, so anything that works with assignees imports this one file and the two features
/// can never drift into two spellings of the same thing (A1).
export '../../features/email/email_models.dart'
    show EmailToken, EmailRoleLevel, EmailRoleOption, EmailRoleGroup;

/// Whether an assignee names a person or a seat (A2), as the backend's `AssigneeKind` spells it.
///
/// Constants rather than an enum because an assignee *is* an [EmailToken] read back, and
/// [EmailToken.type] is a string: the two never have to be translated into each other. There is
/// deliberately no CUSTOMER here — work is always somebody internal's (see
/// `AssigneePickerField`).
abstract final class AssigneeKind {
  /// One named internal user.
  static const user = 'USER';

  /// A role at a level: everyone holding that seat, now.
  static const role = 'ROLE';
}

/// One person an assignee reaches (the backend's `AssigneeDtos.PersonDto`).
///
/// The same class reads a hit from the people search the "Person…" chip uses, which calls the
/// address `email` rather than `address`: one shape for "a person the picker knows about", so the
/// chip that names somebody just picked reads exactly like the chip that names a role's holder.
@immutable
class AssigneePerson {
  /// Null only for a person the server would not name — a customer login is never shown staff.
  final int? userId;
  final String name;

  /// Their email address; null when they have none on file, or when they are masked.
  final String? address;

  const AssigneePerson({this.userId, required this.name, this.address});

  factory AssigneePerson.fromJson(Map<String, dynamic> json) => AssigneePerson(
        userId: (json['userId'] as num?)?.toInt(),
        name: json['name'] as String? ?? json['username'] as String? ?? '',
        address: json['address'] as String? ?? json['email'] as String?,
      );

  /// "Bob Smith <bob@company.com>", or the name alone when there is no address to show.
  String get display {
    final at = address;
    if (at == null || at.isEmpty) return name.isEmpty ? '—' : name;
    if (name.isEmpty || name == at) return at;
    return '$name <$at>';
  }
}

/// One assignee as it reads back (the `assignees` of a task, the backend's `AssigneeDto`): the
/// pick itself, what to call it, and who it reaches right now.
///
/// [people] is read when the record is read and never stored, so a role assignee follows the
/// customer's POC book (A2). It is empty for a seat nobody holds — which is what [resolved] false
/// says — so an unheld role still shows in the list as unassigned rather than quietly vanishing.
@immutable
class Assignee {
  /// [AssigneeKind.user] or [AssigneeKind.role].
  final String kind;

  /// Set on a USER assignee.
  final int? userId;

  /// An EmailRole key such as COLLECTION_POC, on a ROLE assignee.
  final String? role;

  /// An [EmailRoleLevel] on a ROLE assignee: the customer's POC book, or the POC on the record
  /// itself. Null on a role stored before levels, which the server reads as customer level.
  final String? level;

  /// What to call it: the person's name, or the role with its level — "Sales POC (this invoice)".
  final String label;

  /// Whether it reaches anybody at all right now.
  final bool resolved;

  /// Who it reaches, primary first. Empty for an unheld seat, and for a viewer who may not see
  /// staff identity at all.
  final List<AssigneePerson> people;

  const Assignee({
    required this.kind,
    this.userId,
    this.role,
    this.level,
    required this.label,
    this.resolved = false,
    this.people = const [],
  });

  factory Assignee.fromJson(Map<String, dynamic> json) {
    final kind = json['kind'] as String? ?? AssigneeKind.user;
    final role = json['role'] as String?;
    return Assignee(
      kind: kind,
      userId: (json['userId'] as num?)?.toInt(),
      role: role,
      level: json['level'] as String?,
      label: json['label'] as String? ??
          (role == null ? 'Unknown user' : assigneeRoleKeyLabel(role)),
      resolved: json['resolved'] as bool? ?? false,
      people: ((json['people'] as List?) ?? const [])
          .cast<Map>()
          .map((m) => AssigneePerson.fromJson(m.cast<String, dynamic>()))
          .toList(),
    );
  }

  bool get isUser => kind == AssigneeKind.user;
  bool get isRole => kind == AssigneeKind.role;

  /// The token that would pick this assignee again, for a form opened on an existing record.
  /// Null for a row that names neither a person nor a role, which nothing the app writes can
  /// produce — a token is never invented for it, so the edit form drops it rather than saving
  /// something the server would refuse.
  EmailToken? get token {
    if (isUser && userId != null) return EmailToken.user(userId!);
    if (isRole && role != null) return EmailToken.role(role!, level: level);
    return null;
  }

  /// How the assignee reads in a list: a person by name, a role with who holds it — "Collection
  /// POC · Anil", "Collection POC · Anil + 2 more". A seat nobody holds says so, because that is
  /// the thing worth noticing about it.
  String get display {
    // A USER assignee's label is already the person's own name.
    if (isUser) return label;
    if (!resolved) return '$label · nobody assigned';
    return switch (people.length) {
      // Resolved but not shown: the viewer may not see staff identity.
      0 => label,
      1 => '$label · ${people.single.name}',
      2 => '$label · ${people[0].name}, ${people[1].name}',
      _ => '$label · ${people.first.name} + ${people.length - 1} more',
    };
  }

  /// Everyone a role reaches, one per line with their address, for where [display] names them in
  /// short. Null when there is nothing the display does not already say.
  String? get reaches =>
      people.length < 2 ? null : people.map((p) => p.display).join('\n');
}

/// The tokens that would pick these assignees again: what an edit form starts from (A1).
List<EmailToken> assigneeTokens(Iterable<Assignee> assignees) =>
    [for (final a in assignees) if (a.token != null) a.token!];

/// How a role reads on a chip: the role, and who it reaches on this record. One person is named
/// in full, two by name, and from three on "Anil + 2 more", so a chip cut short still says how
/// many there are; the tooltip ([assigneeRoleTooltip]) gives each one's address.
///
/// Which level the role is at is normally said once, by the group heading it sits under; pass
/// [levelled] for a chip that stands away from its heading — one already in the box, or one role
/// offered at both levels, where "Sales POC" twice would read as the same seat.
///
/// A null [EmailRoleOption.resolved] means there is no record to resolve against yet — a rule's
/// assignees are picked before any record exists — and the role is then named alone rather than
/// claiming to reach nobody.
String assigneeRoleText(EmailRoleOption r, {bool levelled = false}) {
  final role = levelled ? r.labelWithLevel : r.label;
  if (r.resolved == null) return role;
  if (r.resolved == false) return '$role · nobody assigned';
  return switch (r.people.length) {
    0 => role,
    1 => '$role · ${r.people.single.display}',
    2 => '$role · ${r.people[0].name}, ${r.people[1].name}',
    _ => '$role · ${r.people.first.name} + ${r.people.length - 1} more',
  };
}

/// Everyone a role reaches, one per line, for the chip's tooltip — the same rule the email form
/// uses: said only where the chip itself had to shorten the list.
String? assigneeRoleTooltip(EmailRoleOption r) =>
    r.people.length < 2 ? null : r.people.map((p) => p.display).join('\n');

/// COLLECTION_POC → "Collection POC", for a role nothing described: a token stored on an
/// automation rule whose record type no longer offers that seat still has to read as something.
String assigneeRoleKeyLabel(String? key) {
  final words = (key ?? '')
      .split('_')
      .where((w) => w.isNotEmpty)
      // POC is an initialism, not a word: "Poc" would read as a typo.
      .map((w) => w == 'POC' ? w : '${w[0]}${w.substring(1).toLowerCase()}')
      .toList();
  return words.isEmpty ? '—' : words.join(' ');
}
