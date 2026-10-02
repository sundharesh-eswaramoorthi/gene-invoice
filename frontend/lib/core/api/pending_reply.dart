import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/approvals/approval_providers.dart';
import '../unsaved_changes.dart';

/// The change a gated write was held as (B2): enough to say so and to open it. Every field is
/// optional on the wire; a customer login, or an older server, may send fewer (G10).
class PendingChangeRef {
  /// Null only when the reply names no change at all, in its body or its `Location`.
  final int? id;
  final String summary;
  final String? operation;
  final String? operationLabel;
  final String? regionName;

  /// How many people other than the maker can approve it; 0 means nobody can yet.
  final int? approverCount;

  /// The change this one replaced, when it replaced the maker's own earlier request.
  final int? supersededId;

  /// The server's own sentence: "Above South's approval limit of … It takes effect once …".
  final String? message;

  const PendingChangeRef({
    this.id,
    this.summary = '',
    this.operation,
    this.operationLabel,
    this.regionName,
    this.approverCount,
    this.supersededId,
    this.message,
  });

  factory PendingChangeRef.fromJson(Map<String, dynamic> json,
          {int? fallbackId, String? message}) =>
      PendingChangeRef(
        id: (json['id'] as num?)?.toInt() ?? fallbackId,
        summary: json['summary'] as String? ?? '',
        operation: json['operation'] as String?,
        operationLabel: json['operationLabel'] as String?,
        regionName: json['regionName'] as String?,
        approverCount: (json['approverCount'] as num?)?.toInt(),
        supersededId: (json['supersededId'] as num?)?.toInt(),
        message: message,
      );
}

/// The held change a reply announces, or null when the write took effect.
///
/// A gated write (§6.6) answers 202 when it is held: the change is in `pendingChange`, and its
/// address in `Location`. There is no record id in such a reply, so a caller asks this **before**
/// reading one (critic §3.11). Any 202 from these endpoints is a hold, even one whose body names
/// no change, so nothing held is ever announced as saved (G14).
PendingChangeRef? pendingFrom(Response<dynamic> res) {
  if (res.statusCode != 202) return null;
  final data = res.data;
  final body = data is Map ? data.cast<String, dynamic>() : const <String, dynamic>{};
  final change = body['pendingChange'];
  final fromLocation = pendingIdFromLocation(res.headers.value('location'));
  final message = body['message'] is String ? body['message'] as String : null;
  if (change is Map) {
    return PendingChangeRef.fromJson(change.cast<String, dynamic>(),
        fallbackId: fromLocation, message: message);
  }
  return PendingChangeRef(id: fromLocation, message: message);
}

/// The change's id from a `Location` of `/api/approvals/{id}`.
int? pendingIdFromLocation(String? location) {
  final m = RegExp(r'/approvals/(\d+)$').firstMatch(location ?? '');
  return m == null ? null : int.parse(m.group(1)!);
}

/// The words of the notice for [p]: [message] (or "Sent for approval: …") and, when nobody can
/// approve it yet, what to do about that.
String sentForApprovalText(PendingChangeRef p, {String? message}) {
  final lead =
      message ?? (p.summary.isEmpty ? 'Sent for approval' : 'Sent for approval: ${p.summary}');
  if (p.approverCount != 0) return lead;
  final where = p.regionName ?? 'this region';
  return '$lead. No one else can approve changes in $where yet. Ask an administrator.';
}

/// Tells the user their change waits for someone else, with a way to open it. The router and the
/// unsaved-changes guard are taken now, so View still works once the page or dialog that asked
/// has gone.
void showSentForApproval(BuildContext context, PendingChangeRef p, {String? message}) =>
    sentForApprovalNotice(context)(p, message: message);

typedef SentForApprovalNotice = void Function(PendingChangeRef p, {String? message});

/// [showSentForApproval] with the messenger taken too, for a page to take before it sends: the
/// reply can arrive after the user has left it, and they still learn their change is waiting.
SentForApprovalNotice sentForApprovalNotice(BuildContext context) {
  final messenger = ScaffoldMessenger.maybeOf(context);
  final router = GoRouter.maybeOf(context);
  final unsaved = ProviderScope.containerOf(context, listen: false).read(unsavedChangesProvider);
  return (p, {message}) {
    if (messenger == null || !messenger.mounted) return;
    final id = p.id;
    messenger.showSnackBar(SnackBar(
      content: Text(sentForApprovalText(p, message: message)),
      action: id == null || router == null
          ? null
          : SnackBarAction(
              label: 'View',
              onPressed: () async {
                if (await unsaved.mayLeave()) router.go('/approvals/$id');
              },
            ),
    ));
  };
}

/// After a change is held: the badge, every record banner and the lists that count or flag it.
void invalidateApprovals(WidgetRef ref) => approvalsChanged(ref);
