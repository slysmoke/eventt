import 'package:drift/drift.dart';

@DataClassName('PriceAlert')
class PriceAlerts extends Table {
  IntColumn get id => integer().autoIncrement()();
  IntColumn get typeId => integer()();
  IntColumn get regionId => integer()();
  RealColumn get targetPrice => real()();
  /// 'above' or 'below'
  TextColumn get condition => text()();
  /// Whether the alert has been triggered
  BoolColumn get triggered => boolean().withDefault(const Constant(false))();
  DateTimeColumn get createdAt => dateTime()();
}
