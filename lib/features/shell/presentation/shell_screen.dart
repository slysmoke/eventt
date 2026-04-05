import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/sde/sde_provider.dart';
import 'app_route.dart';
import 'sidebar.dart';

class ShellScreen extends ConsumerWidget {
  const ShellScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final route = ref.watch(currentRouteProvider);
    // Kick off SDE init on first build; watch for download progress banner.
    final sdeState = ref.watch(sdeInitProvider);

    return Scaffold(
      body: Column(
        children: [
          if (sdeState.isDownloading || (!sdeState.isReady && sdeState.error == null))
            LinearProgressIndicator(
              // null = indeterminate (animated); switch to determinate once we have real progress
              value: sdeState.downloadProgress > 0 ? sdeState.downloadProgress : null,
              minHeight: 3,
            ),
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
