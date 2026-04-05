/// Result of MACD computation. All lists have the same length as the input.
/// Values are null during the EMA warmup period.
class MacdResult {
  final List<double?> macd;
  final List<double?> signal;
  final List<double?> histogram;

  const MacdResult({
    required this.macd,
    required this.signal,
    required this.histogram,
  });
}

/// Stateless indicator math matching Evernus defaults:
/// - SMA period: 20
/// - MACD fast=5, slow=15, signal=5
/// - EMA uses alpha = 1/period (Wilder smoothing)
class MarketIndicators {
  MarketIndicators._();

  /// Simple Moving Average. Returns null for the first [period]-1 values.
  static List<double?> computeSma(List<double> values, {required int period}) {
    final result = List<double?>.filled(values.length, null);
    for (var i = period - 1; i < values.length; i++) {
      var sum = 0.0;
      for (var j = i - period + 1; j <= i; j++) {
        sum += values[j];
      }
      result[i] = sum / period;
    }
    return result;
  }

  /// MACD = EMA(fast) − EMA(slow); Signal = EMA(MACD, signalPeriod).
  /// Uses Wilder smoothing: alpha = 1/period.
  /// Values are null during warmup (first slow-1 entries for MACD;
  /// additionally first signalPeriod-1 entries after that for Signal).
  static MacdResult computeMacd(
    List<double> values, {
    int fast = 5,
    int slow = 15,
    int signalPeriod = 5,
  }) {
    final n = values.length;
    final macd = List<double?>.filled(n, null);
    final signal = List<double?>.filled(n, null);
    final histogram = List<double?>.filled(n, null);

    if (n == 0) return MacdResult(macd: macd, signal: signal, histogram: histogram);

    final alphaFast = 1.0 / fast;
    final alphaSlow = 1.0 / slow;
    final alphaSignal = 1.0 / signalPeriod;

    // Seed EMAs at first value (warmup = 0-based index slow-1)
    var emaFast = values[0];
    var emaSlow = values[0];

    for (var i = 1; i < slow - 1; i++) {
      emaFast = alphaFast * values[i] + (1 - alphaFast) * emaFast;
      emaSlow = alphaSlow * values[i] + (1 - alphaSlow) * emaSlow;
    }

    // From index slow-1 onwards we have a valid MACD
    double? emaSignal;

    for (var i = slow - 1; i < n; i++) {
      if (i > 0) {
        emaFast = alphaFast * values[i] + (1 - alphaFast) * emaFast;
        emaSlow = alphaSlow * values[i] + (1 - alphaSlow) * emaSlow;
      }
      final m = emaFast - emaSlow;
      macd[i] = m;

      if (emaSignal == null) {
        emaSignal = m;
      } else {
        emaSignal = alphaSignal * m + (1 - alphaSignal) * emaSignal;
      }
      signal[i] = emaSignal;
      histogram[i] = m - emaSignal;
    }

    return MacdResult(macd: macd, signal: signal, histogram: histogram);
  }
}
