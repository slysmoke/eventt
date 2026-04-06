import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/database/database_provider.dart';
import '../../../core/esi/esi_provider.dart';
import '../data/corporation_repository.dart';

enum _Step {
  fetching,
  done;

  String get message => switch (this) {
        _Step.fetching => 'Fetching corporation information…',
        _Step.done => 'Corporation added successfully',
      };
}

class AddCorporationDialog extends ConsumerStatefulWidget {
  const AddCorporationDialog({super.key});

  /// Convenience method — shows the dialog and returns when complete.
  static Future<void> show(BuildContext context) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const AddCorporationDialog(),
    );
  }

  @override
  ConsumerState<AddCorporationDialog> createState() =>
      _AddCorporationDialogState();
}

class _AddCorporationDialogState extends ConsumerState<AddCorporationDialog> {
  final _controller = TextEditingController();
  _Step? _step;
  String? _error;
  String? _corpName;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _addCorporation() async {
    final input = _controller.text.trim();
    final corporationId = int.tryParse(input);

    if (corporationId == null || corporationId <= 0) {
      setState(() {
        _error = 'Please enter a valid corporation ID (positive integer)';
      });
      return;
    }

    setState(() {
      _step = _Step.fetching;
      _error = null;
      _corpName = null;
    });

    try {
      final db = ref.read(databaseProvider);
      final esiClient = ref.read(esiClientProvider);

      final repo = CorporationRepository(esi: esiClient, db: db);
      final corporation = await repo.fetchAndSave(corporationId);

      if (corporation == null) {
        setState(() {
          _error =
              'Could not find corporation with ID $corporationId.\nPlease check the ID and try again.';
          _step = null;
        });
        return;
      }

      setState(() {
        _step = _Step.done;
        _corpName = corporation.name;
      });

      // Auto-dismiss after success
      await Future.delayed(const Duration(milliseconds: 800));
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = 'Failed to add corporation: $e';
          _step = null;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isLoading = _step != null;
    final hasError = _error != null;
    final isSuccess = _step == _Step.done;

    return AlertDialog(
      title: const Text('Add Corporation'),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (!isLoading && !hasError && !isSuccess) ...[
                Text(
                  'Enter the corporation ID to add it to your database.',
                  style: theme.textTheme.bodyMedium,
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _controller,
                  keyboardType: const TextInputType.numberWithOptions(),
                  decoration: const InputDecoration(
                    labelText: 'Corporation ID',
                    hintText: 'e.g., 109299958',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  onSubmitted: (_) => _addCorporation(),
                ),
                const SizedBox(height: 8),
                Text(
                  'You can find corporation IDs on zKillboard or EVE Who.',
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ] else if (isLoading) ...[
                const CircularProgressIndicator(),
                const SizedBox(height: 24),
                Text(
                  _step!.message,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium,
                ),
              ] else if (isSuccess) ...[
                Icon(Icons.check_circle,
                    size: 48, color: theme.colorScheme.primary),
                const SizedBox(height: 16),
                Text(
                  'Corporation added!',
                  style: theme.textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                if (_corpName != null)
                  Text(
                    _corpName!,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
              ] else if (hasError) ...[
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
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        if (!isLoading && !isSuccess)
          FilledButton(
            onPressed: _addCorporation,
            child: const Text('Add'),
          ),
        if (hasError)
          FilledButton(
            onPressed: _addCorporation,
            child: const Text('Retry'),
          ),
      ],
    );
  }
}
