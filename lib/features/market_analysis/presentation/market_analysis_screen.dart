import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'import_analysis_tab.dart';
import 'inter_region_analysis_tab.dart';
import 'ore_reprocessing_tab.dart';
import 'region_analysis_tab.dart';
import 'scrapmetal_reprocessing_tab.dart';

/// Main Market Analysis screen with 5 tabs (Evernus-style).
class MarketAnalysisScreen extends ConsumerStatefulWidget {
  const MarketAnalysisScreen({super.key});

  @override
  ConsumerState<MarketAnalysisScreen> createState() =>
      _MarketAnalysisScreenState();
}

class _MarketAnalysisScreenState extends ConsumerState<MarketAnalysisScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 5, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Tab bar
        TabBar(
          controller: _tabController,
          labelColor: theme.colorScheme.primary,
          unselectedLabelColor: theme.colorScheme.onSurfaceVariant,
          indicatorColor: theme.colorScheme.primary,
          tabs: const [
            Tab(icon: Icon(Icons.location_on, size: 16), text: 'Region'),
            Tab(
                icon: Icon(Icons.compare_arrows, size: 16),
                text: 'Inter-Region'),
            Tab(icon: Icon(Icons.import_export, size: 16), text: 'Importing'),
            Tab(icon: Icon(Icons.diamond, size: 16), text: 'Ore Reproc.'),
            Tab(
                icon: Icon(Icons.recycling, size: 16),
                text: 'Scrapmetal Reproc.'),
          ],
        ),
        const Divider(height: 1),
        // Tab views
        Expanded(
          child: TabBarView(
            controller: _tabController,
            children: const [
              RegionAnalysisTab(),
              InterRegionAnalysisTab(),
              ImportAnalysisTab(),
              OreReprocessingTab(),
              ScrapmetalReprocessingTab(),
            ],
          ),
        ),
      ],
    );
  }
}

