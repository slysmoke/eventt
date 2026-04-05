import 'package:drift/drift.dart';

/// Generic key-value store for app settings (e.g. sde_version, active_character_id).
@DataClassName('AppSetting')
class AppSettings extends Table {
  TextColumn get key => text()();
  TextColumn get value => text()();

  @override
  Set<Column> get primaryKey => {key};
}
