import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class MarketAnalysisScreen extends StatelessWidget {
  const MarketAnalysisScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.candlestick_chart,
      title: 'Market Analysis',
      description: 'SMA, MACD and volume analysis per item type and region.',
    );
  }
}
