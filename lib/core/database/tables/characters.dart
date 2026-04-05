import 'package:drift/drift.dart';

class Characters extends Table {
  // EVE character IDs can be large — use integer (64-bit in drift on desktop)
  IntColumn get id => integer()();
  TextColumn get name => text()();
  IntColumn get corporationId => integer().nullable()();
  TextColumn get corporationName => text().nullable()();
  TextColumn get portraitUrl => text().nullable()();
  DateTimeColumn get addedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
