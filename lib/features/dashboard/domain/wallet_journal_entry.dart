class WalletJournalEntry {
  final int id;
  final String refType;
  final double amount;
  final DateTime date;

  const WalletJournalEntry({
    required this.id,
    required this.refType,
    required this.amount,
    required this.date,
  });
}
