import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_client.dart';
import '../../core/field_limits.dart';
import '../../core/format.dart';
import '../../shared/widgets/detail_scaffold.dart';
import 'email_models.dart';
import 'email_providers.dart';

class GmailConnectionScreen extends ConsumerStatefulWidget {
  const GmailConnectionScreen({super.key});

  @override
  ConsumerState<GmailConnectionScreen> createState() => _GmailConnectionScreenState();
}

class _GmailConnectionScreenState extends ConsumerState<GmailConnectionScreen> {
  static const _fields = {'clientId', 'clientSecret', 'refreshToken'};

  final _formKey = GlobalKey<FormState>();
  final _clientId = TextEditingController();
  final _clientSecret = TextEditingController();
  final _refreshToken = TextEditingController();
  bool _showSecret = false;
  bool _showToken = false;

  bool _busy = false;
  String? _error;

  Map<String, String> _fieldErrors = const {};

  bool _prefilled = false;

  @override
  void dispose() {
    _clientId.dispose();
    _clientSecret.dispose();
    _refreshToken.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final allowed = ref.watch(canConnectGmailProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Gmail connection'),
        actions: [
          if (allowed)
            IconButton(
              tooltip: 'Refresh',
              icon: const Icon(Icons.refresh),
              onPressed: _busy ? null : () => ref.invalidate(myGmailProvider),
            ),
        ],
      ),
      body: !allowed
          ? const RecordUnavailable(
              message: 'Gmail is connected by the staff who send email from the app.')
          : ref.watch(myGmailProvider).when(
                skipError: true,
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => _LoadError(
                  message: 'Could not load your Gmail connection: ${apiErrorMessage(e)}',
                  onRetry: () => ref.invalidate(myGmailProvider),
                ),
                data: _page,
              ),
    );
  }

  Widget _page(GmailConnection g) {
    _prefill(g);
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (!g.configured) ...[
                const _Notice(
                  text: 'Email delivery is not configured, so Gmail cannot be connected. Emails '
                      'are saved in the app but not sent.',
                ),
                const SizedBox(height: 12),
              ],
              _statusCard(g),
              const SizedBox(height: 12),
              _formCard(g),
              const SizedBox(height: 12),
              Card(
                clipBehavior: Clip.antiAlias,
                child: ExpansionTile(
                  initiallyExpanded: !g.exists,
                  leading: const Icon(Icons.help_outline),
                  title: const Text('How to get these'),
                  childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  expandedCrossAxisAlignment: CrossAxisAlignment.start,
                  children: const [_HowTo()],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _prefill(GmailConnection g) {
    if (_prefilled) return;
    final clientId = g.clientId;
    if (clientId == null || clientId.isEmpty) return;
    _prefilled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _clientId.text.isEmpty) _clientId.text = clientId;
    });
  }

  Widget _statusCard(GmailConnection g) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final muted = theme.textTheme.bodySmall;
    final errorStyle = TextStyle(color: scheme.error);

    final IconData icon;
    final Color color;
    final String title;
    final lines = <Widget>[];
    if (g.isConnected) {
      icon = Icons.check_circle_outline;
      color = Colors.green.shade700;
      final address = g.gmailAddress ?? 'your Gmail';
      title = g.connectedAt == null
          ? 'Connected as $address'
          : 'Connected as $address since ${formatDateTime(g.connectedAt)}';
      lines.add(Text(
        g.lastSyncedAt == null
            ? 'Not checked for replies yet'
            : 'Last checked for replies ${formatDateTime(g.lastSyncedAt)}',
        style: muted,
      ));
      final failed = g.lastSyncError;
      if (failed != null && failed.isNotEmpty) {
        lines.add(Text('The last check failed: $failed', style: errorStyle));
      }
    } else if (g.needsReconnect) {
      icon = Icons.error_outline;
      color = scheme.error;
      title = 'Needs renewing';
      if (g.gmailAddress != null) lines.add(Text(g.gmailAddress!, style: muted));
      lines.add(Text(
        g.reason == null || g.reason!.isEmpty
            ? 'Google no longer accepts this Gmail connection. Reconnect Gmail.'
            : g.reason!,
        style: errorStyle,
      ));
    } else {
      icon = Icons.link_off;
      color = scheme.outline;
      title = 'Not connected';
      lines.add(Text(
        'Email you write in the app is saved but not sent until you connect your Gmail. It is '
        'then sent from your Gmail address, and replies to it come back to your Inbox here.',
        style: muted,
      ));
    }
    if (g.serviceError != null && g.serviceError!.isNotEmpty) {
      lines.add(Text(g.serviceError!, style: errorStyle));
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(width: 12),
            Expanded(
              child: SelectionArea(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: theme.textTheme.titleMedium),
                    for (final line in lines)
                      Padding(padding: const EdgeInsets.only(top: 4), child: line),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _formCard(GmailConnection g) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final enabled = g.configured && !_busy;

    Widget secretToggle(bool shown, String what, VoidCallback onPressed) => IconButton(
          tooltip: shown ? 'Hide $what' : 'Show $what',
          icon: Icon(shown ? Icons.visibility_off : Icons.visibility),
          onPressed: onPressed,
        );

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                g.exists ? 'Connect again, or with other values' : 'Connect your Gmail',
                style: theme.textTheme.titleMedium,
              ),
              const SizedBox(height: 4),
              Text(
                'From your own Google Cloud OAuth client and Google\'s OAuth Playground — see '
                '"How to get these" below.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _clientId,
                enabled: enabled,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: 'Client ID',
                  hintText: '….apps.googleusercontent.com',
                  errorText: _fieldErrors['clientId'],
                ),
                inputFormatters: [LengthLimitingTextInputFormatter(FieldLimits.gmailClientId)],
                validator: (v) => (v ?? '').trim().isEmpty ? 'Enter the client ID' : null,
                onChanged: (_) => _clearFieldError('clientId'),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _clientSecret,
                enabled: enabled,
                obscureText: !_showSecret,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: 'Client secret',
                  errorText: _fieldErrors['clientSecret'],
                  suffixIcon: secretToggle(_showSecret, 'client secret',
                      () => setState(() => _showSecret = !_showSecret)),
                ),
                inputFormatters: [
                  LengthLimitingTextInputFormatter(FieldLimits.gmailClientSecret),
                ],
                validator: (v) => (v ?? '').trim().isEmpty ? 'Enter the client secret' : null,
                onChanged: (_) => _clearFieldError('clientSecret'),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _refreshToken,
                enabled: enabled,
                obscureText: !_showToken,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: 'Refresh token',
                  errorText: _fieldErrors['refreshToken'],
                  suffixIcon: secretToggle(_showToken, 'refresh token',
                      () => setState(() => _showToken = !_showToken)),
                ),
                inputFormatters: [
                  LengthLimitingTextInputFormatter(FieldLimits.gmailRefreshToken),
                ],
                validator: (v) => (v ?? '').trim().isEmpty ? 'Enter the refresh token' : null,
                onChanged: (_) => _clearFieldError('refreshToken'),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    icon: _busy
                        ? const SizedBox(
                            width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.link, size: 18),
                    label: Text(g.exists ? 'Reconnect' : 'Connect'),
                    onPressed: enabled ? _connect : null,
                  ),
                  if (g.exists)
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(foregroundColor: scheme.error),
                      icon: const Icon(Icons.link_off, size: 18),
                      label: const Text('Disconnect'),
                      onPressed: enabled ? _disconnect : null,
                    ),
                ],
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Semantics(
                    container: true,
                    liveRegion: true,
                    label: _error!,
                    excludeSemantics: true,
                    child: SelectableText(_error!, style: TextStyle(color: scheme.error)),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  void _clearFieldError(String field) {
    if (!_fieldErrors.containsKey(field)) return;
    setState(() => _fieldErrors = {..._fieldErrors}..remove(field));
  }

  Future<void> _connect() async {
    setState(() {
      _error = null;
      _fieldErrors = const {};
    });
    if (!(_formKey.currentState?.validate() ?? false)) return;

    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _busy = true);
    try {
      final res = await container.read(dioProvider).put('/api/me/gmail', data: {
        'clientId': _clientId.text.trim(),
        'clientSecret': _clientSecret.text.trim(),
        'refreshToken': _refreshToken.text.trim(),
      });
      final connected = GmailConnection.fromJson((res.data as Map).cast<String, dynamic>());
      if (mounted) {
        _clientSecret.clear();
        _refreshToken.clear();
        _clientId.text = _clientId.text.trim();
      }
      invalidateGmailStatus(container);
      final address = connected.gmailAddress;
      messenger.showSnackBar(SnackBar(
          content: Text(address == null ? 'Gmail connected' : 'Gmail connected as $address')));
    } catch (e) {
      if (mounted) {
        final fields = _fieldErrorsOf(e);
        setState(() {
          _fieldErrors = {
            for (final f in fields.entries)
              if (_fields.contains(f.key)) f.key: f.value,
          };
          _error = fields.isNotEmpty && _fieldErrors.length == fields.length
              ? null
              : apiErrorMessage(e);
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  static Map<String, String> _fieldErrorsOf(Object error) {
    final data = error is DioException ? error.response?.data : null;
    final fields = data is Map ? data['fieldErrors'] : null;
    if (fields is! Map) return const {};
    return {for (final e in fields.entries) '${e.key}': '${e.value}'};
  }

  Future<void> _disconnect() async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Disconnect Gmail?'),
        content: const SizedBox(
          width: 420,
          child: Text(
            'Email you write in the app will be saved but not sent until you connect again, and '
            'replies will no longer reach your Inbox. Google is asked to cancel the refresh token.',
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.of(context).pop(true), child: const Text('Disconnect')),
        ],
      ),
    );
    if (sure != true || !mounted) return;

    final container = ProviderScope.containerOf(context, listen: false);
    final messenger = ScaffoldMessenger.of(context);
    setState(() {
      _busy = true;
      _error = null;
      _fieldErrors = const {};
    });
    try {
      await container.read(dioProvider).delete('/api/me/gmail');
      invalidateGmailStatus(container);
      messenger.showSnackBar(const SnackBar(content: Text('Gmail disconnected')));
    } catch (e) {
      if (mounted) setState(() => _error = apiErrorMessage(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

class _HowTo extends StatelessWidget {
  const _HowTo();

  static const _playground = 'https://developers.google.com/oauthplayground';
  static const _scopes =
      'https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.readonly';

  static const _steps = [
    'In Google Cloud Console, create a project, then **APIs & Services → Library → Gmail API → '
        'Enable**.',
    '**Google Auth Platform / OAuth consent screen**: user type **External**, app name and '
        'your email; under **Audience → Test users** add your Gmail address.',
    '**Credentials → Create credentials → OAuth client ID**, type **Web application**, '
        'authorized redirect URI **$_playground**. Copy the **Client ID** and **Client secret**.',
    'Open **$_playground**, click the gear icon, tick **Use your own OAuth credentials** and paste '
        'the client ID and secret.',
    'In Step 1 enter **$_scopes**, click **Authorize APIs**, sign in with your Gmail and allow '
        'access (on "Google hasn\'t verified this app" choose **Continue**).',
    'In Step 2 click **Exchange authorization code for tokens** and copy the **Refresh token**.',
    'Paste the three into this page and click **Connect**.',
  ];

  static const _note = 'While the Google app is in **Testing**, Google expires the refresh token '
      'after **7 days**; repeat steps 4–7 and click **Reconnect**. Personal Gmail sends at most '
      'about 500 messages a day, and each copy counts.';

  static TextSpan _rich(String text) {
    final parts = text.split('**');
    return TextSpan(children: [
      for (var i = 0; i < parts.length; i++)
        TextSpan(
          text: parts[i],
          style: i.isOdd ? const TextStyle(fontWeight: FontWeight.w600) : null,
        ),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SelectionArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final (i, step) in _steps.indexed)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(width: 24, child: Text('${i + 1}.')),
                  Expanded(child: Text.rich(_rich(step))),
                ],
              ),
            ),
          const SizedBox(height: 4),
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: scheme.tertiaryContainer,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.schedule, size: 18, color: scheme.onTertiaryContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text.rich(_rich(_note),
                      style: TextStyle(color: scheme.onTertiaryContainer)),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  final String text;
  const _Notice({required this.text});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: scheme.tertiaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 18, color: scheme.onTertiaryContainer),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(color: scheme.onTertiaryContainer))),
        ],
      ),
    );
  }
}

class _LoadError extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _LoadError({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error),
              const SizedBox(height: 8),
              Text(message, textAlign: TextAlign.center),
              TextButton(onPressed: onRetry, child: const Text('Try again')),
            ],
          ),
        ),
      );
}

class UserGmailStatus extends ConsumerWidget {
  final int userId;
  const UserGmailStatus({super.key, required this.userId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref.watch(userGmailProvider(userId)).when(
          loading: () => const ReadOnlyValue('…'),
          error: (e, _) => Tooltip(
            message: apiErrorMessage(e),
            child: const ReadOnlyValue('Unavailable'),
          ),
          data: (g) {
            if (g.isConnected) {
              return ReadOnlyValue(
                  g.gmailAddress == null ? 'Connected' : 'Connected as ${g.gmailAddress}');
            }
            if (g.needsReconnect) {
              final reason = g.reason;
              return reason == null || reason.isEmpty
                  ? const ReadOnlyValue('Needs renewing')
                  : Tooltip(message: reason, child: const ReadOnlyValue('Needs renewing'));
            }
            return const ReadOnlyValue('Not connected');
          },
        );
  }
}
