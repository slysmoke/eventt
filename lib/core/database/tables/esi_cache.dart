import 'package:drift/drift.dart';

@DataClassName('EsiCacheEntry')
class EsiCache extends Table {
  TextColumn get url => text()();
  TextColumn get etag => text().nullable()();
  DateTimeColumn get expiresAt => dateTime()();
  DateTimeColumn get cachedAt => dateTime()();
  // JSON response body as string
  TextColumn get body => text()();

  @override
  Set<Column> get primaryKey => {url};
}
