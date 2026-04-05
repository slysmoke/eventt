import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/app_database.dart';
import '../database/database_provider.dart';

const _kActiveCharId = 'active_character_id';

/// The currently selected character. Null when no character is added/selected.
final activeCharacterProvider =
    AsyncNotifierProvider<ActiveCharacterNotifier, Character?>(
  ActiveCharacterNotifier.new,
);

class ActiveCharacterNotifier extends AsyncNotifier<Character?> {
  @override
  Future<Character?> build() async {
    final db = ref.watch(databaseProvider);
    final idStr = await db.getSetting(_kActiveCharId);
    if (idStr == null || idStr.isEmpty) return _firstCharacter(db);
    final id = int.tryParse(idStr);
    if (id == null) return _firstCharacter(db);
    final found = await (db.select(db.characters)
          ..where((c) => c.id.equals(id)))
        .getSingleOrNull();
    return found ?? _firstCharacter(db);
  }

  Future<void> setActive(Character? character) async {
    final db = ref.read(databaseProvider);
    await db.setSetting(
        _kActiveCharId, character == null ? '' : character.id.toString());
    ref.invalidateSelf();
    await future;
  }

  static Future<Character?> _firstCharacter(AppDatabase db) =>
      db.select(db.characters).getSingleOrNull();
}
