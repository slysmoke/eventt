import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_client.dart';
import '../../../core/esi/esi_provider.dart';
import '../domain/wallet_journal_entry.dart';

final walletRepositoryProvider = Provider<WalletRepository>(
  (ref) => WalletRepository(ref.watch(esiClientProvider)),
);

class WalletRepository {
  final EsiClient _esi;

  WalletRepository(this._esi);

  Future<double> fetchBalance({
    required int characterId,
    required String accessToken,
  }) async {
    final r = await _esi.get(
      '/characters/$characterId/wallet/',
      accessToken: accessToken,
    );
    return (r.data as num).toDouble();
  }

  /// Fetches wallet journal for the last [maxDays] days.
  /// ESI returns entries newest-first; stops fetching when all entries on a
  /// page are older than the cutoff.
  Future<List<WalletJournalEntry>> fetchJournal({
    required int characterId,
    required String accessToken,
    int maxDays = 30,
  }) async {
    final cutoff = DateTime.now().toUtc().subtract(Duration(days: maxDays));
    final entries = <WalletJournalEntry>[];
    int page = 1;

    while (true) {
      final r = await _esi.get(
        '/characters/$characterId/wallet/journal/',
        queryParameters: {'page': page},
        accessToken: accessToken,
      );

      final list = r.data as List<dynamic>;
      if (list.isEmpty) break;

      bool allOld = true;
      for (final raw in list) {
        final date = DateTime.parse(raw['date'] as String).toUtc();
        if (date.isBefore(cutoff)) continue;
        allOld = false;
        entries.add(WalletJournalEntry(
          id: raw['id'] as int,
          refType: raw['ref_type'] as String,
          amount: (raw['amount'] as num).toDouble(),
          date: date,
        ));
      }

      if (allOld) break;

      final xPages = int.tryParse(
              r.headers.value('x-pages') ?? '1') ??
          1;
      if (page >= xPages) break;
      page++;
    }

    return entries;
  }
}
