import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/database/database_provider.dart';
import '../../../core/esi/esi_provider.dart';
import '../data/character_repository.dart';

enum _Step {
  openingBrowser,
  awaitingCallback,
  fetchingInfo,
  done;

  String get message => switch (this) {
        _Step.openingBrowser => 'Opening browser…',
        _Step.awaitingCallback => 'Waiting for EVE SSO login…\n'
            'Complete the login in your browser.',
        _Step.fetchingInfo => 'Fetching character information…',
        _Step.done => 'Done',
      };
}

class AddCharacterDialog extends ConsumerStatefulWidget {
  const AddCharacterDialog({super.key});

  /// Convenience method — shows the dialog and returns when complete.
  static Future<void> show(BuildContext context) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const AddCharacterDialog(),
    );
  }

  @override
  ConsumerState<AddCharacterDialog> createState() => _AddCharacterDialogState();
}

class _AddCharacterDialogState extends ConsumerState<AddCharacterDialog> {
  _Step _step = _Step.openingBrowser;
  String? _error;

  @override
  void initState() {
    super.initState();
    // Start auth immediately when dialog opens
    WidgetsBinding.instance.addPostFrameCallback((_) => _startAuth());
  }

  Future<void> _startAuth() async {
    setState(() {
      _step = _Step.openingBrowser;
      _error = null;
    });

    try {
      final authService = ref.read(eveAuthServiceProvider);
      final db = ref.read(databaseProvider);
      final esiClient = ref.read(esiClientProvider);
      final tokenStorage = ref.read(tokenStorageProvider);

      setState(() => _step = _Step.awaitingCallback);
      final characterId = await authService.authenticate();

      setState(() => _step = _Step.fetchingInfo);
      final accessToken = await tokenStorage.getAccessToken(characterId);

      final repo = CharacterRepository(esi: esiClient, db: db);
      await repo.fetchAndSave(characterId, accessToken!);

      // Auto-select if first character
      final characters = await db.select(db.characters).get();
      if (characters.length == 1) {
        ref.read(activeCharacterIdProvider.notifier).select(characterId);
      }

      setState(() => _step = _Step.done);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        setState(() => _error = _friendlyError(e));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasError = _error != null;

    return AlertDialog(
      title: const Text('Add Character'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (!hasError) ...[
              const CircularProgressIndicator(),
              const SizedBox(height: 24),
              Text(
                _step.message,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium,
              ),
            ] else ...[
              Icon(Icons.error_outline,
                  size: 48, color: theme.colorScheme.error),
              const SizedBox(height: 16),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        if (hasError)
          FilledButton(
            onPressed: _startAuth,
            child: const Text('Retry'),
          ),
      ],
    );
  }

  String _friendlyError(Object e) {
    final msg = e.toString();
    if (msg.contains('SocketException') || msg.contains('Connection refused')) {
      return 'Could not start local callback server on port 8000.\n'
          'Make sure no other application is using that port.';
    }
    if (msg.contains('timed out')) {
      return 'Authentication timed out.\nDid you complete the login in the browser?';
    }
    if (msg.contains('state mismatch')) {
      return 'Security error: OAuth state mismatch.\nPlease try again.';
    }
    return msg;
  }
}
