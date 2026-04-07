import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:window_manager/window_manager.dart';

import '../../../core/margin/margin_calculator.dart';
import '../../../core/market_log/market_log_parser.dart';
import '../../../core/settings/margin_settings_provider.dart';
import '../data/market_log_watcher.dart';

// Whether the tool is in compact floating mode.
class _FloatingNotifier extends Notifier<bool> {
  @override
  bool build() => false;
  void set(bool value) => state = value;
}

final _floatingProvider =
    NotifierProvider<_FloatingNotifier, bool>(_FloatingNotifier.new);

class MarginToolScreen extends ConsumerWidget {
  const MarginToolScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final floating = ref.watch(_floatingProvider);
    return floating
        ? const _FloatingMarginTool()
        : const _FullMarginTool();
  }
}

// ---------------------------------------------------------------------------
// Full in-app view
// ---------------------------------------------------------------------------

class _FullMarginTool extends ConsumerStatefulWidget {
  const _FullMarginTool();

  @override
  ConsumerState<_FullMarginTool> createState() => _FullMarginToolState();
}

class _FullMarginToolState extends ConsumerState<_FullMarginTool> {
  final _sellCtrl = TextEditingController();
  final _buyCtrl = TextEditingController();
  MarginResult? _result;

  @override
  void dispose() {
    _sellCtrl.dispose();
    _buyCtrl.dispose();
    super.dispose();
  }

  void _calculate() {
    final sell = double.tryParse(_sellCtrl.text.replaceAll(',', '.'));
    final buy = double.tryParse(_buyCtrl.text.replaceAll(',', '.'));
    if (sell == null || buy == null) return;
    final settings = ref.read(marginSettingsProvider).value;
    final params = settings?.marginParams ?? const MarginParams();
    setState(() => _result = MarginCalculator.compute(
          buyPrice: buy,
          sellPrice: sell,
          params: params,
        ));
  }

  Future<void> _pasteFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim() ?? '';
    final value = double.tryParse(text.replaceAll(',', '.'));
    if (value == null || value <= 0) return;
    // If sell field is empty, fill sell; otherwise fill buy.
    if (_sellCtrl.text.isEmpty) {
      _sellCtrl.text = text;
    } else {
      _buyCtrl.text = text;
    }
    _calculate();
  }

  Future<void> _float() async {
    await windowManager.setSize(const Size(420, 320));
    await windowManager.setAlwaysOnTop(true);
    if (Platform.isLinux || Platform.isWindows) {
      await windowManager.setTitleBarStyle(TitleBarStyle.hidden);
    }
    ref.read(_floatingProvider.notifier).set(true);
  }

  @override
  Widget build(BuildContext context) {
    final settingsAsync = ref.watch(marginSettingsProvider);
    final logFile = ref.watch(latestMarketLogProvider).value;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Margin Tool'),
        actions: [
          IconButton(
            icon: const Icon(Icons.open_in_new),
            tooltip: 'Floating mode',
            onPressed: _float,
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (logFile != null) _LogFileBadge(logFile),
            const SizedBox(height: 12),
            _PriceInputRow(
              sellCtrl: _sellCtrl,
              buyCtrl: _buyCtrl,
              onCalculate: _calculate,
              onPaste: _pasteFromClipboard,
            ),
            const SizedBox(height: 16),
            if (_result != null) ...[
              _MarginResults(result: _result!),
              const SizedBox(height: 16),
            ],
            settingsAsync.when(
              data: (s) => _SettingsPanel(settings: s),
              loading: () => const LinearProgressIndicator(),
              error: (_, __) => const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Compact floating view
// ---------------------------------------------------------------------------

class _FloatingMarginTool extends ConsumerStatefulWidget {
  const _FloatingMarginTool();

  @override
  ConsumerState<_FloatingMarginTool> createState() =>
      _FloatingMarginToolState();
}

class _FloatingMarginToolState extends ConsumerState<_FloatingMarginTool> {
  final _sellCtrl = TextEditingController();
  final _buyCtrl = TextEditingController();
  MarginResult? _result;

  @override
  void dispose() {
    _sellCtrl.dispose();
    _buyCtrl.dispose();
    super.dispose();
  }

  void _calculate() {
    final sell = double.tryParse(_sellCtrl.text.replaceAll(',', '.'));
    final buy = double.tryParse(_buyCtrl.text.replaceAll(',', '.'));
    if (sell == null || buy == null) return;
    final settings = ref.read(marginSettingsProvider).value;
    final params = settings?.marginParams ?? const MarginParams();
    setState(() => _result = MarginCalculator.compute(
          buyPrice: buy,
          sellPrice: sell,
          params: params,
        ));
  }

  Future<void> _restore() async {
    await windowManager.setAlwaysOnTop(false);
    await windowManager.setTitleBarStyle(TitleBarStyle.normal);
    await windowManager.setSize(const Size(1280, 800));
    ref.read(_floatingProvider.notifier).set(false);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final logFile = ref.watch(latestMarketLogProvider).value;

    return Material(
      color: cs.surface,
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onPanStart: (_) => windowManager.startDragging(),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            children: [
              // Title bar row
              Row(
                children: [
                  const Icon(Icons.calculate, size: 14),
                  const SizedBox(width: 4),
                  Expanded(
                    child: Text(
                      logFile != null
                          ? '${logFile.itemName} · ${logFile.regionName}'
                          : 'Margin Tool',
                      style: Theme.of(context).textTheme.labelMedium,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, size: 16),
                    tooltip: 'Exit floating mode',
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                    onPressed: _restore,
                  ),
                ],
              ),
              const Divider(height: 8),
              // Inputs
              Row(
                children: [
                  Expanded(
                    child: _PriceField(
                      ctrl: _sellCtrl,
                      label: 'Sell',
                      onSubmit: (_) => _calculate(),
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: _PriceField(
                      ctrl: _buyCtrl,
                      label: 'Buy',
                      onSubmit: (_) => _calculate(),
                    ),
                  ),
                  const SizedBox(width: 6),
                  ElevatedButton(
                    onPressed: _calculate,
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 8, vertical: 12),
                      minimumSize: Size.zero,
                    ),
                    child: const Text('Calc'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (_result != null)
                _CompactResults(result: _result!)
              else
                const SizedBox.shrink(),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Shared widgets
// ---------------------------------------------------------------------------

class _LogFileBadge extends StatelessWidget {
  final MarketLogFile logFile;
  const _LogFileBadge(this.logFile);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: cs.primaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(Icons.description, size: 16, color: cs.onPrimaryContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  logFile.itemName,
                  style: TextStyle(
                    color: cs.onPrimaryContainer,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text(
                  '${logFile.regionName}'
                  '${logFile.typeId != null ? " · type ${logFile.typeId}" : ""}',
                  style: TextStyle(
                    color: cs.onPrimaryContainer.withAlpha(180),
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          if (logFile.bestSellPrice != null)
            _Chip(
              label: 'Sell ${_fmt(logFile.bestSellPrice!)}',
              color: cs.onPrimaryContainer,
            ),
          const SizedBox(width: 6),
          if (logFile.bestBuyPrice != null)
            _Chip(
              label: 'Buy ${_fmt(logFile.bestBuyPrice!)}',
              color: cs.onPrimaryContainer,
            ),
        ],
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  final String label;
  final Color color;
  const _Chip({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        border: Border.all(color: color.withAlpha(100)),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(label, style: TextStyle(color: color, fontSize: 11)),
    );
  }
}

class _PriceInputRow extends StatelessWidget {
  final TextEditingController sellCtrl;
  final TextEditingController buyCtrl;
  final VoidCallback onCalculate;
  final VoidCallback onPaste;

  const _PriceInputRow({
    required this.sellCtrl,
    required this.buyCtrl,
    required this.onCalculate,
    required this.onPaste,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: _PriceField(
            ctrl: sellCtrl,
            label: 'Sell price',
            onSubmit: (_) => onCalculate(),
          ),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: _PriceField(
            ctrl: buyCtrl,
            label: 'Buy price',
            onSubmit: (_) => onCalculate(),
          ),
        ),
        const SizedBox(width: 8),
        ElevatedButton.icon(
          onPressed: onCalculate,
          icon: const Icon(Icons.calculate, size: 18),
          label: const Text('Calculate'),
        ),
        const SizedBox(width: 8),
        OutlinedButton.icon(
          onPressed: onPaste,
          icon: const Icon(Icons.content_paste, size: 18),
          label: const Text('Paste'),
        ),
      ],
    );
  }
}

class _PriceField extends StatelessWidget {
  final TextEditingController ctrl;
  final String label;
  final ValueChanged<String>? onSubmit;

  const _PriceField({required this.ctrl, required this.label, this.onSubmit});

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: ctrl,
      keyboardType: const TextInputType.numberWithOptions(decimal: true),
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        isDense: true,
      ),
      onSubmitted: onSubmit,
    );
  }
}

class _MarginResults extends StatelessWidget {
  final MarginResult result;
  const _MarginResults({required this.result});

  @override
  Widget build(BuildContext context) {
    final isProfit = result.isProfit;
    final marginColor =
        isProfit ? Colors.green.shade400 : Colors.red.shade400;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${isProfit ? "+" : ""}${_fmt(result.profit)} ISK  '
              '(${result.margin.toStringAsFixed(2)}%)',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    color: marginColor,
                    fontWeight: FontWeight.bold,
                  ),
            ),
            const SizedBox(height: 12),
            _Row('Sell price', _fmt(result.sellPrice)),
            _Row('Buy price', '− ${_fmt(result.buyPrice)}'),
            _Row('Broker fee (sell)', '− ${_fmt(result.brokerFee)}'),
            _Row('Sales tax', '− ${_fmt(result.salesTax)}'),
            if (result.buyBrokerFee > 0)
              _Row('Broker fee (buy)', '− ${_fmt(result.buyBrokerFee)}'),
            const Divider(height: 16),
            _Row('Net profit', _fmt(result.profit),
                bold: true,
                valueColor: marginColor),
            const SizedBox(height: 8),
            _Row('Break-even sell', _fmt(result.breakEvenSellPrice)),
            Row(
              children: [
                Expanded(
                  child: _Row(
                    'Order #1 price',
                    _fmt(result.orderOneSellPrice),
                    bold: true,
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, size: 16),
                  tooltip: 'Copy to clipboard',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(
                        text: result.orderOneSellPrice.toStringAsFixed(2)));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                          content: Text('Copied order #1 price'),
                          duration: Duration(seconds: 1)),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  final String label;
  final String value;
  final bool bold;
  final Color? valueColor;

  const _Row(this.label, this.value,
      {this.bold = false, this.valueColor});

  @override
  Widget build(BuildContext context) {
    final style = bold
        ? const TextStyle(fontWeight: FontWeight.bold)
        : null;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: style),
          Text(value,
              style: style?.copyWith(color: valueColor) ??
                  TextStyle(color: valueColor)),
        ],
      ),
    );
  }
}

class _CompactResults extends StatelessWidget {
  final MarginResult result;
  const _CompactResults({required this.result});

  @override
  Widget build(BuildContext context) {
    final isProfit = result.isProfit;
    final color =
        isProfit ? Colors.green.shade400 : Colors.red.shade400;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              '${isProfit ? "+" : ""}${_fmt(result.profit)}',
              style: TextStyle(color: color, fontWeight: FontWeight.bold),
            ),
            Text(
              '${result.margin.toStringAsFixed(2)}%',
              style: TextStyle(color: color),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text('BE: ${_fmt(result.breakEvenSellPrice)}',
                style: const TextStyle(fontSize: 11)),
            GestureDetector(
              onTap: () => Clipboard.setData(ClipboardData(
                  text: result.orderOneSellPrice.toStringAsFixed(2))),
              child: Row(
                children: [
                  const Icon(Icons.copy, size: 12),
                  const SizedBox(width: 2),
                  Text('#1: ${_fmt(result.orderOneSellPrice)}',
                      style: const TextStyle(
                          fontSize: 11, fontWeight: FontWeight.bold)),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Settings panel
// ---------------------------------------------------------------------------

class _SettingsPanel extends ConsumerStatefulWidget {
  final MarginSettings settings;
  const _SettingsPanel({required this.settings});

  @override
  ConsumerState<_SettingsPanel> createState() => _SettingsPanelState();
}

class _SettingsPanelState extends ConsumerState<_SettingsPanel> {
  late TextEditingController _brokerCtrl;
  late TextEditingController _taxCtrl;
  late bool _buyFee;
  String? _logDir;

  @override
  void initState() {
    super.initState();
    _brokerCtrl = TextEditingController(
        text: widget.settings.brokerFeePct.toStringAsFixed(1));
    _taxCtrl = TextEditingController(
        text: widget.settings.salesTaxPct.toStringAsFixed(1));
    _buyFee = widget.settings.includeBuyBrokerFee;
    _logDir = widget.settings.marketLogDir;
  }

  @override
  void dispose() {
    _brokerCtrl.dispose();
    _taxCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final broker = double.tryParse(_brokerCtrl.text.replaceAll(',', '.'));
    final tax = double.tryParse(_taxCtrl.text.replaceAll(',', '.'));
    if (broker == null || tax == null) return;
    final updated = widget.settings.copyWith(
      brokerFeePct: broker,
      salesTaxPct: tax,
      includeBuyBrokerFee: _buyFee,
      marketLogDir: _logDir,
    );
    await ref.read(marginSettingsProvider.notifier).save(updated);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Settings saved'), duration: Duration(seconds: 1)),
      );
    }
  }

  Future<void> _pickDir() async {
    final result = await FilePicker.getDirectoryPath(
      dialogTitle: 'Select EVE Market Logs directory',
    );
    if (result != null) {
      setState(() => _logDir = result);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Settings',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _brokerCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Broker fee %',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                        decimal: true),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _taxCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Sales tax %',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                        decimal: true),
                  ),
                ),
                const SizedBox(width: 12),
                Row(
                  children: [
                    Checkbox(
                      value: _buyFee,
                      onChanged: (v) =>
                          setState(() => _buyFee = v ?? false),
                    ),
                    const Text('Buy-side broker fee'),
                  ],
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: InputDecorator(
                    decoration: const InputDecoration(
                      labelText: 'Market log directory',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                    child: Text(
                      _logDir ?? '(not set)',
                      style: const TextStyle(fontSize: 13),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  onPressed: _pickDir,
                  icon: const Icon(Icons.folder_open, size: 18),
                  label: const Text('Browse'),
                ),
                if (_logDir != null) ...[
                  const SizedBox(width: 4),
                  IconButton(
                    icon: const Icon(Icons.clear, size: 18),
                    tooltip: 'Clear',
                    onPressed: () => setState(() => _logDir = null),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: ElevatedButton(
                onPressed: _save,
                child: const Text('Save'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

String _fmt(double v) {
  if (v.abs() >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
  if (v.abs() >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
  if (v.abs() >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
  return v.toStringAsFixed(2);
}
