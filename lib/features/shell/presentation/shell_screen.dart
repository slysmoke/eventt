import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/sde/sde_provider.dart';
import '../../../core/updater/update_banner.dart';
import '../../../core/updater/updater_provider.dart';
import 'app_route.dart';
import 'sidebar.dart';

class ShellScreen extends ConsumerStatefulWidget {
  const ShellScreen({super.key});

  @override
  ConsumerState<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends ConsumerState<ShellScreen> {
  @override
  void initState() {
    super.initState();
    // Check for updates on startup (non-blocking)
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(updateCheckProvider.notifier).check();
    });
  }

  @override
  Widget build(BuildContext context) {
    final route = ref.watch(currentRouteProvider);
    final sdeState = ref.watch(sdeInitProvider);

    return Scaffold(
      body: Column(
        children: [
          // SDE download progress
          if (sdeState.isDownloading || (!sdeState.isReady && sdeState.error == null))
            LinearProgressIndicator(
              value: sdeState.downloadProgress > 0 ? sdeState.downloadProgress : null,
              minHeight: 3,
            ),
          // Update available banner
          const UpdateBanner(),
          Expanded(
            child: Row(
              children: [
                const Sidebar(),
                const VerticalDivider(width: 1),
                Expanded(child: route.screen),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
