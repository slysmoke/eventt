import 'package:drift/drift.dart';

class Corporations extends Table {
  IntColumn get id => integer()();
  TextColumn get name => text()();
  TextColumn get ticker => text().nullable()();
  IntColumn get ceoId => integer().nullable()();
  TextColumn get ceoName => text().nullable()();
  IntColumn get allianceId => integer().nullable()();
  TextColumn get allianceName => text().nullable()();
  DateTimeColumn get addedAt => dateTime()();

  @override
  Set<Column> get primaryKey => {id};
}
