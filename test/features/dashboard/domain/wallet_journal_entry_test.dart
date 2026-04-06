import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/dashboard/domain/wallet_journal_entry.dart';

void main() {
  group('WalletJournalEntry', () {
    test('can be created', () {
      final entry = WalletJournalEntry(
        id: 1,
        refType: 'market_transaction',
        amount: 1000.0,
        date: DateTime(2024, 1, 1),
      );
      expect(entry.id, 1);
      expect(entry.refType, 'market_transaction');
      expect(entry.amount, 1000.0);
      expect(entry.date, DateTime(2024, 1, 1));
    });

    test('handles negative amounts', () {
      final entry = WalletJournalEntry(
        id: 2,
        refType: 'broker_fee',
        amount: -50.0,
        date: DateTime(2024, 1, 1),
      );
      expect(entry.amount, -50.0);
    });
  });
}
