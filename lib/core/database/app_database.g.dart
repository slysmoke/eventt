// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'app_database.dart';

// ignore_for_file: type=lint
class $CharactersTable extends Characters
    with TableInfo<$CharactersTable, Character> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $CharactersTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<int> id = GeneratedColumn<int>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _nameMeta = const VerificationMeta('name');
  @override
  late final GeneratedColumn<String> name = GeneratedColumn<String>(
    'name',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _corporationIdMeta = const VerificationMeta(
    'corporationId',
  );
  @override
  late final GeneratedColumn<int> corporationId = GeneratedColumn<int>(
    'corporation_id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _corporationNameMeta = const VerificationMeta(
    'corporationName',
  );
  @override
  late final GeneratedColumn<String> corporationName = GeneratedColumn<String>(
    'corporation_name',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _portraitUrlMeta = const VerificationMeta(
    'portraitUrl',
  );
  @override
  late final GeneratedColumn<String> portraitUrl = GeneratedColumn<String>(
    'portrait_url',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _addedAtMeta = const VerificationMeta(
    'addedAt',
  );
  @override
  late final GeneratedColumn<DateTime> addedAt = GeneratedColumn<DateTime>(
    'added_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    name,
    corporationId,
    corporationName,
    portraitUrl,
    addedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'characters';
  @override
  VerificationContext validateIntegrity(
    Insertable<Character> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('name')) {
      context.handle(
        _nameMeta,
        name.isAcceptableOrUnknown(data['name']!, _nameMeta),
      );
    } else if (isInserting) {
      context.missing(_nameMeta);
    }
    if (data.containsKey('corporation_id')) {
      context.handle(
        _corporationIdMeta,
        corporationId.isAcceptableOrUnknown(
          data['corporation_id']!,
          _corporationIdMeta,
        ),
      );
    }
    if (data.containsKey('corporation_name')) {
      context.handle(
        _corporationNameMeta,
        corporationName.isAcceptableOrUnknown(
          data['corporation_name']!,
          _corporationNameMeta,
        ),
      );
    }
    if (data.containsKey('portrait_url')) {
      context.handle(
        _portraitUrlMeta,
        portraitUrl.isAcceptableOrUnknown(
          data['portrait_url']!,
          _portraitUrlMeta,
        ),
      );
    }
    if (data.containsKey('added_at')) {
      context.handle(
        _addedAtMeta,
        addedAt.isAcceptableOrUnknown(data['added_at']!, _addedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_addedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  Character map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return Character(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      name: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}name'],
      )!,
      corporationId: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}corporation_id'],
      ),
      corporationName: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}corporation_name'],
      ),
      portraitUrl: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}portrait_url'],
      ),
      addedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}added_at'],
      )!,
    );
  }

  @override
  $CharactersTable createAlias(String alias) {
    return $CharactersTable(attachedDatabase, alias);
  }
}

class Character extends DataClass implements Insertable<Character> {
  final int id;
  final String name;
  final int? corporationId;
  final String? corporationName;
  final String? portraitUrl;
  final DateTime addedAt;
  const Character({
    required this.id,
    required this.name,
    this.corporationId,
    this.corporationName,
    this.portraitUrl,
    required this.addedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    map['name'] = Variable<String>(name);
    if (!nullToAbsent || corporationId != null) {
      map['corporation_id'] = Variable<int>(corporationId);
    }
    if (!nullToAbsent || corporationName != null) {
      map['corporation_name'] = Variable<String>(corporationName);
    }
    if (!nullToAbsent || portraitUrl != null) {
      map['portrait_url'] = Variable<String>(portraitUrl);
    }
    map['added_at'] = Variable<DateTime>(addedAt);
    return map;
  }

  CharactersCompanion toCompanion(bool nullToAbsent) {
    return CharactersCompanion(
      id: Value(id),
      name: Value(name),
      corporationId: corporationId == null && nullToAbsent
          ? const Value.absent()
          : Value(corporationId),
      corporationName: corporationName == null && nullToAbsent
          ? const Value.absent()
          : Value(corporationName),
      portraitUrl: portraitUrl == null && nullToAbsent
          ? const Value.absent()
          : Value(portraitUrl),
      addedAt: Value(addedAt),
    );
  }

  factory Character.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return Character(
      id: serializer.fromJson<int>(json['id']),
      name: serializer.fromJson<String>(json['name']),
      corporationId: serializer.fromJson<int?>(json['corporationId']),
      corporationName: serializer.fromJson<String?>(json['corporationName']),
      portraitUrl: serializer.fromJson<String?>(json['portraitUrl']),
      addedAt: serializer.fromJson<DateTime>(json['addedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'name': serializer.toJson<String>(name),
      'corporationId': serializer.toJson<int?>(corporationId),
      'corporationName': serializer.toJson<String?>(corporationName),
      'portraitUrl': serializer.toJson<String?>(portraitUrl),
      'addedAt': serializer.toJson<DateTime>(addedAt),
    };
  }

  Character copyWith({
    int? id,
    String? name,
    Value<int?> corporationId = const Value.absent(),
    Value<String?> corporationName = const Value.absent(),
    Value<String?> portraitUrl = const Value.absent(),
    DateTime? addedAt,
  }) => Character(
    id: id ?? this.id,
    name: name ?? this.name,
    corporationId: corporationId.present
        ? corporationId.value
        : this.corporationId,
    corporationName: corporationName.present
        ? corporationName.value
        : this.corporationName,
    portraitUrl: portraitUrl.present ? portraitUrl.value : this.portraitUrl,
    addedAt: addedAt ?? this.addedAt,
  );
  Character copyWithCompanion(CharactersCompanion data) {
    return Character(
      id: data.id.present ? data.id.value : this.id,
      name: data.name.present ? data.name.value : this.name,
      corporationId: data.corporationId.present
          ? data.corporationId.value
          : this.corporationId,
      corporationName: data.corporationName.present
          ? data.corporationName.value
          : this.corporationName,
      portraitUrl: data.portraitUrl.present
          ? data.portraitUrl.value
          : this.portraitUrl,
      addedAt: data.addedAt.present ? data.addedAt.value : this.addedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('Character(')
          ..write('id: $id, ')
          ..write('name: $name, ')
          ..write('corporationId: $corporationId, ')
          ..write('corporationName: $corporationName, ')
          ..write('portraitUrl: $portraitUrl, ')
          ..write('addedAt: $addedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    name,
    corporationId,
    corporationName,
    portraitUrl,
    addedAt,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is Character &&
          other.id == this.id &&
          other.name == this.name &&
          other.corporationId == this.corporationId &&
          other.corporationName == this.corporationName &&
          other.portraitUrl == this.portraitUrl &&
          other.addedAt == this.addedAt);
}

class CharactersCompanion extends UpdateCompanion<Character> {
  final Value<int> id;
  final Value<String> name;
  final Value<int?> corporationId;
  final Value<String?> corporationName;
  final Value<String?> portraitUrl;
  final Value<DateTime> addedAt;
  const CharactersCompanion({
    this.id = const Value.absent(),
    this.name = const Value.absent(),
    this.corporationId = const Value.absent(),
    this.corporationName = const Value.absent(),
    this.portraitUrl = const Value.absent(),
    this.addedAt = const Value.absent(),
  });
  CharactersCompanion.insert({
    this.id = const Value.absent(),
    required String name,
    this.corporationId = const Value.absent(),
    this.corporationName = const Value.absent(),
    this.portraitUrl = const Value.absent(),
    required DateTime addedAt,
  }) : name = Value(name),
       addedAt = Value(addedAt);
  static Insertable<Character> custom({
    Expression<int>? id,
    Expression<String>? name,
    Expression<int>? corporationId,
    Expression<String>? corporationName,
    Expression<String>? portraitUrl,
    Expression<DateTime>? addedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (name != null) 'name': name,
      if (corporationId != null) 'corporation_id': corporationId,
      if (corporationName != null) 'corporation_name': corporationName,
      if (portraitUrl != null) 'portrait_url': portraitUrl,
      if (addedAt != null) 'added_at': addedAt,
    });
  }

  CharactersCompanion copyWith({
    Value<int>? id,
    Value<String>? name,
    Value<int?>? corporationId,
    Value<String?>? corporationName,
    Value<String?>? portraitUrl,
    Value<DateTime>? addedAt,
  }) {
    return CharactersCompanion(
      id: id ?? this.id,
      name: name ?? this.name,
      corporationId: corporationId ?? this.corporationId,
      corporationName: corporationName ?? this.corporationName,
      portraitUrl: portraitUrl ?? this.portraitUrl,
      addedAt: addedAt ?? this.addedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (name.present) {
      map['name'] = Variable<String>(name.value);
    }
    if (corporationId.present) {
      map['corporation_id'] = Variable<int>(corporationId.value);
    }
    if (corporationName.present) {
      map['corporation_name'] = Variable<String>(corporationName.value);
    }
    if (portraitUrl.present) {
      map['portrait_url'] = Variable<String>(portraitUrl.value);
    }
    if (addedAt.present) {
      map['added_at'] = Variable<DateTime>(addedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('CharactersCompanion(')
          ..write('id: $id, ')
          ..write('name: $name, ')
          ..write('corporationId: $corporationId, ')
          ..write('corporationName: $corporationName, ')
          ..write('portraitUrl: $portraitUrl, ')
          ..write('addedAt: $addedAt')
          ..write(')'))
        .toString();
  }
}

class $EsiCacheTable extends EsiCache
    with TableInfo<$EsiCacheTable, EsiCacheEntry> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $EsiCacheTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _urlMeta = const VerificationMeta('url');
  @override
  late final GeneratedColumn<String> url = GeneratedColumn<String>(
    'url',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _etagMeta = const VerificationMeta('etag');
  @override
  late final GeneratedColumn<String> etag = GeneratedColumn<String>(
    'etag',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _expiresAtMeta = const VerificationMeta(
    'expiresAt',
  );
  @override
  late final GeneratedColumn<DateTime> expiresAt = GeneratedColumn<DateTime>(
    'expires_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _cachedAtMeta = const VerificationMeta(
    'cachedAt',
  );
  @override
  late final GeneratedColumn<DateTime> cachedAt = GeneratedColumn<DateTime>(
    'cached_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _bodyMeta = const VerificationMeta('body');
  @override
  late final GeneratedColumn<String> body = GeneratedColumn<String>(
    'body',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [url, etag, expiresAt, cachedAt, body];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'esi_cache';
  @override
  VerificationContext validateIntegrity(
    Insertable<EsiCacheEntry> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('url')) {
      context.handle(
        _urlMeta,
        url.isAcceptableOrUnknown(data['url']!, _urlMeta),
      );
    } else if (isInserting) {
      context.missing(_urlMeta);
    }
    if (data.containsKey('etag')) {
      context.handle(
        _etagMeta,
        etag.isAcceptableOrUnknown(data['etag']!, _etagMeta),
      );
    }
    if (data.containsKey('expires_at')) {
      context.handle(
        _expiresAtMeta,
        expiresAt.isAcceptableOrUnknown(data['expires_at']!, _expiresAtMeta),
      );
    } else if (isInserting) {
      context.missing(_expiresAtMeta);
    }
    if (data.containsKey('cached_at')) {
      context.handle(
        _cachedAtMeta,
        cachedAt.isAcceptableOrUnknown(data['cached_at']!, _cachedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_cachedAtMeta);
    }
    if (data.containsKey('body')) {
      context.handle(
        _bodyMeta,
        body.isAcceptableOrUnknown(data['body']!, _bodyMeta),
      );
    } else if (isInserting) {
      context.missing(_bodyMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {url};
  @override
  EsiCacheEntry map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return EsiCacheEntry(
      url: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}url'],
      )!,
      etag: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}etag'],
      ),
      expiresAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}expires_at'],
      )!,
      cachedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}cached_at'],
      )!,
      body: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}body'],
      )!,
    );
  }

  @override
  $EsiCacheTable createAlias(String alias) {
    return $EsiCacheTable(attachedDatabase, alias);
  }
}

class EsiCacheEntry extends DataClass implements Insertable<EsiCacheEntry> {
  final String url;
  final String? etag;
  final DateTime expiresAt;
  final DateTime cachedAt;
  final String body;
  const EsiCacheEntry({
    required this.url,
    this.etag,
    required this.expiresAt,
    required this.cachedAt,
    required this.body,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['url'] = Variable<String>(url);
    if (!nullToAbsent || etag != null) {
      map['etag'] = Variable<String>(etag);
    }
    map['expires_at'] = Variable<DateTime>(expiresAt);
    map['cached_at'] = Variable<DateTime>(cachedAt);
    map['body'] = Variable<String>(body);
    return map;
  }

  EsiCacheCompanion toCompanion(bool nullToAbsent) {
    return EsiCacheCompanion(
      url: Value(url),
      etag: etag == null && nullToAbsent ? const Value.absent() : Value(etag),
      expiresAt: Value(expiresAt),
      cachedAt: Value(cachedAt),
      body: Value(body),
    );
  }

  factory EsiCacheEntry.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return EsiCacheEntry(
      url: serializer.fromJson<String>(json['url']),
      etag: serializer.fromJson<String?>(json['etag']),
      expiresAt: serializer.fromJson<DateTime>(json['expiresAt']),
      cachedAt: serializer.fromJson<DateTime>(json['cachedAt']),
      body: serializer.fromJson<String>(json['body']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'url': serializer.toJson<String>(url),
      'etag': serializer.toJson<String?>(etag),
      'expiresAt': serializer.toJson<DateTime>(expiresAt),
      'cachedAt': serializer.toJson<DateTime>(cachedAt),
      'body': serializer.toJson<String>(body),
    };
  }

  EsiCacheEntry copyWith({
    String? url,
    Value<String?> etag = const Value.absent(),
    DateTime? expiresAt,
    DateTime? cachedAt,
    String? body,
  }) => EsiCacheEntry(
    url: url ?? this.url,
    etag: etag.present ? etag.value : this.etag,
    expiresAt: expiresAt ?? this.expiresAt,
    cachedAt: cachedAt ?? this.cachedAt,
    body: body ?? this.body,
  );
  EsiCacheEntry copyWithCompanion(EsiCacheCompanion data) {
    return EsiCacheEntry(
      url: data.url.present ? data.url.value : this.url,
      etag: data.etag.present ? data.etag.value : this.etag,
      expiresAt: data.expiresAt.present ? data.expiresAt.value : this.expiresAt,
      cachedAt: data.cachedAt.present ? data.cachedAt.value : this.cachedAt,
      body: data.body.present ? data.body.value : this.body,
    );
  }

  @override
  String toString() {
    return (StringBuffer('EsiCacheEntry(')
          ..write('url: $url, ')
          ..write('etag: $etag, ')
          ..write('expiresAt: $expiresAt, ')
          ..write('cachedAt: $cachedAt, ')
          ..write('body: $body')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(url, etag, expiresAt, cachedAt, body);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is EsiCacheEntry &&
          other.url == this.url &&
          other.etag == this.etag &&
          other.expiresAt == this.expiresAt &&
          other.cachedAt == this.cachedAt &&
          other.body == this.body);
}

class EsiCacheCompanion extends UpdateCompanion<EsiCacheEntry> {
  final Value<String> url;
  final Value<String?> etag;
  final Value<DateTime> expiresAt;
  final Value<DateTime> cachedAt;
  final Value<String> body;
  final Value<int> rowid;
  const EsiCacheCompanion({
    this.url = const Value.absent(),
    this.etag = const Value.absent(),
    this.expiresAt = const Value.absent(),
    this.cachedAt = const Value.absent(),
    this.body = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  EsiCacheCompanion.insert({
    required String url,
    this.etag = const Value.absent(),
    required DateTime expiresAt,
    required DateTime cachedAt,
    required String body,
    this.rowid = const Value.absent(),
  }) : url = Value(url),
       expiresAt = Value(expiresAt),
       cachedAt = Value(cachedAt),
       body = Value(body);
  static Insertable<EsiCacheEntry> custom({
    Expression<String>? url,
    Expression<String>? etag,
    Expression<DateTime>? expiresAt,
    Expression<DateTime>? cachedAt,
    Expression<String>? body,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (url != null) 'url': url,
      if (etag != null) 'etag': etag,
      if (expiresAt != null) 'expires_at': expiresAt,
      if (cachedAt != null) 'cached_at': cachedAt,
      if (body != null) 'body': body,
      if (rowid != null) 'rowid': rowid,
    });
  }

  EsiCacheCompanion copyWith({
    Value<String>? url,
    Value<String?>? etag,
    Value<DateTime>? expiresAt,
    Value<DateTime>? cachedAt,
    Value<String>? body,
    Value<int>? rowid,
  }) {
    return EsiCacheCompanion(
      url: url ?? this.url,
      etag: etag ?? this.etag,
      expiresAt: expiresAt ?? this.expiresAt,
      cachedAt: cachedAt ?? this.cachedAt,
      body: body ?? this.body,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (url.present) {
      map['url'] = Variable<String>(url.value);
    }
    if (etag.present) {
      map['etag'] = Variable<String>(etag.value);
    }
    if (expiresAt.present) {
      map['expires_at'] = Variable<DateTime>(expiresAt.value);
    }
    if (cachedAt.present) {
      map['cached_at'] = Variable<DateTime>(cachedAt.value);
    }
    if (body.present) {
      map['body'] = Variable<String>(body.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('EsiCacheCompanion(')
          ..write('url: $url, ')
          ..write('etag: $etag, ')
          ..write('expiresAt: $expiresAt, ')
          ..write('cachedAt: $cachedAt, ')
          ..write('body: $body, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $AppSettingsTable extends AppSettings
    with TableInfo<$AppSettingsTable, AppSetting> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $AppSettingsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _keyMeta = const VerificationMeta('key');
  @override
  late final GeneratedColumn<String> key = GeneratedColumn<String>(
    'key',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _valueMeta = const VerificationMeta('value');
  @override
  late final GeneratedColumn<String> value = GeneratedColumn<String>(
    'value',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [key, value];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'app_settings';
  @override
  VerificationContext validateIntegrity(
    Insertable<AppSetting> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('key')) {
      context.handle(
        _keyMeta,
        key.isAcceptableOrUnknown(data['key']!, _keyMeta),
      );
    } else if (isInserting) {
      context.missing(_keyMeta);
    }
    if (data.containsKey('value')) {
      context.handle(
        _valueMeta,
        value.isAcceptableOrUnknown(data['value']!, _valueMeta),
      );
    } else if (isInserting) {
      context.missing(_valueMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {key};
  @override
  AppSetting map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return AppSetting(
      key: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}key'],
      )!,
      value: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}value'],
      )!,
    );
  }

  @override
  $AppSettingsTable createAlias(String alias) {
    return $AppSettingsTable(attachedDatabase, alias);
  }
}

class AppSetting extends DataClass implements Insertable<AppSetting> {
  final String key;
  final String value;
  const AppSetting({required this.key, required this.value});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['key'] = Variable<String>(key);
    map['value'] = Variable<String>(value);
    return map;
  }

  AppSettingsCompanion toCompanion(bool nullToAbsent) {
    return AppSettingsCompanion(key: Value(key), value: Value(value));
  }

  factory AppSetting.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return AppSetting(
      key: serializer.fromJson<String>(json['key']),
      value: serializer.fromJson<String>(json['value']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'key': serializer.toJson<String>(key),
      'value': serializer.toJson<String>(value),
    };
  }

  AppSetting copyWith({String? key, String? value}) =>
      AppSetting(key: key ?? this.key, value: value ?? this.value);
  AppSetting copyWithCompanion(AppSettingsCompanion data) {
    return AppSetting(
      key: data.key.present ? data.key.value : this.key,
      value: data.value.present ? data.value.value : this.value,
    );
  }

  @override
  String toString() {
    return (StringBuffer('AppSetting(')
          ..write('key: $key, ')
          ..write('value: $value')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(key, value);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is AppSetting &&
          other.key == this.key &&
          other.value == this.value);
}

class AppSettingsCompanion extends UpdateCompanion<AppSetting> {
  final Value<String> key;
  final Value<String> value;
  final Value<int> rowid;
  const AppSettingsCompanion({
    this.key = const Value.absent(),
    this.value = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  AppSettingsCompanion.insert({
    required String key,
    required String value,
    this.rowid = const Value.absent(),
  }) : key = Value(key),
       value = Value(value);
  static Insertable<AppSetting> custom({
    Expression<String>? key,
    Expression<String>? value,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (key != null) 'key': key,
      if (value != null) 'value': value,
      if (rowid != null) 'rowid': rowid,
    });
  }

  AppSettingsCompanion copyWith({
    Value<String>? key,
    Value<String>? value,
    Value<int>? rowid,
  }) {
    return AppSettingsCompanion(
      key: key ?? this.key,
      value: value ?? this.value,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (key.present) {
      map['key'] = Variable<String>(key.value);
    }
    if (value.present) {
      map['value'] = Variable<String>(value.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('AppSettingsCompanion(')
          ..write('key: $key, ')
          ..write('value: $value, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $PriceAlertsTable extends PriceAlerts
    with TableInfo<$PriceAlertsTable, PriceAlert> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $PriceAlertsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<int> id = GeneratedColumn<int>(
    'id',
    aliasedName,
    false,
    hasAutoIncrement: true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'PRIMARY KEY AUTOINCREMENT',
    ),
  );
  static const VerificationMeta _typeIdMeta = const VerificationMeta('typeId');
  @override
  late final GeneratedColumn<int> typeId = GeneratedColumn<int>(
    'type_id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _regionIdMeta = const VerificationMeta(
    'regionId',
  );
  @override
  late final GeneratedColumn<int> regionId = GeneratedColumn<int>(
    'region_id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _targetPriceMeta = const VerificationMeta(
    'targetPrice',
  );
  @override
  late final GeneratedColumn<double> targetPrice = GeneratedColumn<double>(
    'target_price',
    aliasedName,
    false,
    type: DriftSqlType.double,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _conditionMeta = const VerificationMeta(
    'condition',
  );
  @override
  late final GeneratedColumn<String> condition = GeneratedColumn<String>(
    'condition',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _triggeredMeta = const VerificationMeta(
    'triggered',
  );
  @override
  late final GeneratedColumn<bool> triggered = GeneratedColumn<bool>(
    'triggered',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("triggered" IN (0, 1))',
    ),
    defaultValue: const Constant(false),
  );
  static const VerificationMeta _createdAtMeta = const VerificationMeta(
    'createdAt',
  );
  @override
  late final GeneratedColumn<DateTime> createdAt = GeneratedColumn<DateTime>(
    'created_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    typeId,
    regionId,
    targetPrice,
    condition,
    triggered,
    createdAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'price_alerts';
  @override
  VerificationContext validateIntegrity(
    Insertable<PriceAlert> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('type_id')) {
      context.handle(
        _typeIdMeta,
        typeId.isAcceptableOrUnknown(data['type_id']!, _typeIdMeta),
      );
    } else if (isInserting) {
      context.missing(_typeIdMeta);
    }
    if (data.containsKey('region_id')) {
      context.handle(
        _regionIdMeta,
        regionId.isAcceptableOrUnknown(data['region_id']!, _regionIdMeta),
      );
    } else if (isInserting) {
      context.missing(_regionIdMeta);
    }
    if (data.containsKey('target_price')) {
      context.handle(
        _targetPriceMeta,
        targetPrice.isAcceptableOrUnknown(
          data['target_price']!,
          _targetPriceMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_targetPriceMeta);
    }
    if (data.containsKey('condition')) {
      context.handle(
        _conditionMeta,
        condition.isAcceptableOrUnknown(data['condition']!, _conditionMeta),
      );
    } else if (isInserting) {
      context.missing(_conditionMeta);
    }
    if (data.containsKey('triggered')) {
      context.handle(
        _triggeredMeta,
        triggered.isAcceptableOrUnknown(data['triggered']!, _triggeredMeta),
      );
    }
    if (data.containsKey('created_at')) {
      context.handle(
        _createdAtMeta,
        createdAt.isAcceptableOrUnknown(data['created_at']!, _createdAtMeta),
      );
    } else if (isInserting) {
      context.missing(_createdAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  PriceAlert map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return PriceAlert(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      typeId: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}type_id'],
      )!,
      regionId: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}region_id'],
      )!,
      targetPrice: attachedDatabase.typeMapping.read(
        DriftSqlType.double,
        data['${effectivePrefix}target_price'],
      )!,
      condition: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}condition'],
      )!,
      triggered: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}triggered'],
      )!,
      createdAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}created_at'],
      )!,
    );
  }

  @override
  $PriceAlertsTable createAlias(String alias) {
    return $PriceAlertsTable(attachedDatabase, alias);
  }
}

class PriceAlert extends DataClass implements Insertable<PriceAlert> {
  final int id;
  final int typeId;
  final int regionId;
  final double targetPrice;

  /// 'above' or 'below'
  final String condition;

  /// Whether the alert has been triggered
  final bool triggered;
  final DateTime createdAt;
  const PriceAlert({
    required this.id,
    required this.typeId,
    required this.regionId,
    required this.targetPrice,
    required this.condition,
    required this.triggered,
    required this.createdAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    map['type_id'] = Variable<int>(typeId);
    map['region_id'] = Variable<int>(regionId);
    map['target_price'] = Variable<double>(targetPrice);
    map['condition'] = Variable<String>(condition);
    map['triggered'] = Variable<bool>(triggered);
    map['created_at'] = Variable<DateTime>(createdAt);
    return map;
  }

  PriceAlertsCompanion toCompanion(bool nullToAbsent) {
    return PriceAlertsCompanion(
      id: Value(id),
      typeId: Value(typeId),
      regionId: Value(regionId),
      targetPrice: Value(targetPrice),
      condition: Value(condition),
      triggered: Value(triggered),
      createdAt: Value(createdAt),
    );
  }

  factory PriceAlert.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return PriceAlert(
      id: serializer.fromJson<int>(json['id']),
      typeId: serializer.fromJson<int>(json['typeId']),
      regionId: serializer.fromJson<int>(json['regionId']),
      targetPrice: serializer.fromJson<double>(json['targetPrice']),
      condition: serializer.fromJson<String>(json['condition']),
      triggered: serializer.fromJson<bool>(json['triggered']),
      createdAt: serializer.fromJson<DateTime>(json['createdAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'typeId': serializer.toJson<int>(typeId),
      'regionId': serializer.toJson<int>(regionId),
      'targetPrice': serializer.toJson<double>(targetPrice),
      'condition': serializer.toJson<String>(condition),
      'triggered': serializer.toJson<bool>(triggered),
      'createdAt': serializer.toJson<DateTime>(createdAt),
    };
  }

  PriceAlert copyWith({
    int? id,
    int? typeId,
    int? regionId,
    double? targetPrice,
    String? condition,
    bool? triggered,
    DateTime? createdAt,
  }) => PriceAlert(
    id: id ?? this.id,
    typeId: typeId ?? this.typeId,
    regionId: regionId ?? this.regionId,
    targetPrice: targetPrice ?? this.targetPrice,
    condition: condition ?? this.condition,
    triggered: triggered ?? this.triggered,
    createdAt: createdAt ?? this.createdAt,
  );
  PriceAlert copyWithCompanion(PriceAlertsCompanion data) {
    return PriceAlert(
      id: data.id.present ? data.id.value : this.id,
      typeId: data.typeId.present ? data.typeId.value : this.typeId,
      regionId: data.regionId.present ? data.regionId.value : this.regionId,
      targetPrice: data.targetPrice.present
          ? data.targetPrice.value
          : this.targetPrice,
      condition: data.condition.present ? data.condition.value : this.condition,
      triggered: data.triggered.present ? data.triggered.value : this.triggered,
      createdAt: data.createdAt.present ? data.createdAt.value : this.createdAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('PriceAlert(')
          ..write('id: $id, ')
          ..write('typeId: $typeId, ')
          ..write('regionId: $regionId, ')
          ..write('targetPrice: $targetPrice, ')
          ..write('condition: $condition, ')
          ..write('triggered: $triggered, ')
          ..write('createdAt: $createdAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    typeId,
    regionId,
    targetPrice,
    condition,
    triggered,
    createdAt,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is PriceAlert &&
          other.id == this.id &&
          other.typeId == this.typeId &&
          other.regionId == this.regionId &&
          other.targetPrice == this.targetPrice &&
          other.condition == this.condition &&
          other.triggered == this.triggered &&
          other.createdAt == this.createdAt);
}

class PriceAlertsCompanion extends UpdateCompanion<PriceAlert> {
  final Value<int> id;
  final Value<int> typeId;
  final Value<int> regionId;
  final Value<double> targetPrice;
  final Value<String> condition;
  final Value<bool> triggered;
  final Value<DateTime> createdAt;
  const PriceAlertsCompanion({
    this.id = const Value.absent(),
    this.typeId = const Value.absent(),
    this.regionId = const Value.absent(),
    this.targetPrice = const Value.absent(),
    this.condition = const Value.absent(),
    this.triggered = const Value.absent(),
    this.createdAt = const Value.absent(),
  });
  PriceAlertsCompanion.insert({
    this.id = const Value.absent(),
    required int typeId,
    required int regionId,
    required double targetPrice,
    required String condition,
    this.triggered = const Value.absent(),
    required DateTime createdAt,
  }) : typeId = Value(typeId),
       regionId = Value(regionId),
       targetPrice = Value(targetPrice),
       condition = Value(condition),
       createdAt = Value(createdAt);
  static Insertable<PriceAlert> custom({
    Expression<int>? id,
    Expression<int>? typeId,
    Expression<int>? regionId,
    Expression<double>? targetPrice,
    Expression<String>? condition,
    Expression<bool>? triggered,
    Expression<DateTime>? createdAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (typeId != null) 'type_id': typeId,
      if (regionId != null) 'region_id': regionId,
      if (targetPrice != null) 'target_price': targetPrice,
      if (condition != null) 'condition': condition,
      if (triggered != null) 'triggered': triggered,
      if (createdAt != null) 'created_at': createdAt,
    });
  }

  PriceAlertsCompanion copyWith({
    Value<int>? id,
    Value<int>? typeId,
    Value<int>? regionId,
    Value<double>? targetPrice,
    Value<String>? condition,
    Value<bool>? triggered,
    Value<DateTime>? createdAt,
  }) {
    return PriceAlertsCompanion(
      id: id ?? this.id,
      typeId: typeId ?? this.typeId,
      regionId: regionId ?? this.regionId,
      targetPrice: targetPrice ?? this.targetPrice,
      condition: condition ?? this.condition,
      triggered: triggered ?? this.triggered,
      createdAt: createdAt ?? this.createdAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (typeId.present) {
      map['type_id'] = Variable<int>(typeId.value);
    }
    if (regionId.present) {
      map['region_id'] = Variable<int>(regionId.value);
    }
    if (targetPrice.present) {
      map['target_price'] = Variable<double>(targetPrice.value);
    }
    if (condition.present) {
      map['condition'] = Variable<String>(condition.value);
    }
    if (triggered.present) {
      map['triggered'] = Variable<bool>(triggered.value);
    }
    if (createdAt.present) {
      map['created_at'] = Variable<DateTime>(createdAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('PriceAlertsCompanion(')
          ..write('id: $id, ')
          ..write('typeId: $typeId, ')
          ..write('regionId: $regionId, ')
          ..write('targetPrice: $targetPrice, ')
          ..write('condition: $condition, ')
          ..write('triggered: $triggered, ')
          ..write('createdAt: $createdAt')
          ..write(')'))
        .toString();
  }
}

class $CorporationsTable extends Corporations
    with TableInfo<$CorporationsTable, Corporation> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $CorporationsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<int> id = GeneratedColumn<int>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _nameMeta = const VerificationMeta('name');
  @override
  late final GeneratedColumn<String> name = GeneratedColumn<String>(
    'name',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _tickerMeta = const VerificationMeta('ticker');
  @override
  late final GeneratedColumn<String> ticker = GeneratedColumn<String>(
    'ticker',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _ceoIdMeta = const VerificationMeta('ceoId');
  @override
  late final GeneratedColumn<int> ceoId = GeneratedColumn<int>(
    'ceo_id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _ceoNameMeta = const VerificationMeta(
    'ceoName',
  );
  @override
  late final GeneratedColumn<String> ceoName = GeneratedColumn<String>(
    'ceo_name',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _allianceIdMeta = const VerificationMeta(
    'allianceId',
  );
  @override
  late final GeneratedColumn<int> allianceId = GeneratedColumn<int>(
    'alliance_id',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _allianceNameMeta = const VerificationMeta(
    'allianceName',
  );
  @override
  late final GeneratedColumn<String> allianceName = GeneratedColumn<String>(
    'alliance_name',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _addedAtMeta = const VerificationMeta(
    'addedAt',
  );
  @override
  late final GeneratedColumn<DateTime> addedAt = GeneratedColumn<DateTime>(
    'added_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    name,
    ticker,
    ceoId,
    ceoName,
    allianceId,
    allianceName,
    addedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'corporations';
  @override
  VerificationContext validateIntegrity(
    Insertable<Corporation> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('name')) {
      context.handle(
        _nameMeta,
        name.isAcceptableOrUnknown(data['name']!, _nameMeta),
      );
    } else if (isInserting) {
      context.missing(_nameMeta);
    }
    if (data.containsKey('ticker')) {
      context.handle(
        _tickerMeta,
        ticker.isAcceptableOrUnknown(data['ticker']!, _tickerMeta),
      );
    }
    if (data.containsKey('ceo_id')) {
      context.handle(
        _ceoIdMeta,
        ceoId.isAcceptableOrUnknown(data['ceo_id']!, _ceoIdMeta),
      );
    }
    if (data.containsKey('ceo_name')) {
      context.handle(
        _ceoNameMeta,
        ceoName.isAcceptableOrUnknown(data['ceo_name']!, _ceoNameMeta),
      );
    }
    if (data.containsKey('alliance_id')) {
      context.handle(
        _allianceIdMeta,
        allianceId.isAcceptableOrUnknown(data['alliance_id']!, _allianceIdMeta),
      );
    }
    if (data.containsKey('alliance_name')) {
      context.handle(
        _allianceNameMeta,
        allianceName.isAcceptableOrUnknown(
          data['alliance_name']!,
          _allianceNameMeta,
        ),
      );
    }
    if (data.containsKey('added_at')) {
      context.handle(
        _addedAtMeta,
        addedAt.isAcceptableOrUnknown(data['added_at']!, _addedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_addedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  Corporation map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return Corporation(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      name: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}name'],
      )!,
      ticker: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}ticker'],
      ),
      ceoId: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}ceo_id'],
      ),
      ceoName: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}ceo_name'],
      ),
      allianceId: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}alliance_id'],
      ),
      allianceName: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}alliance_name'],
      ),
      addedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}added_at'],
      )!,
    );
  }

  @override
  $CorporationsTable createAlias(String alias) {
    return $CorporationsTable(attachedDatabase, alias);
  }
}

class Corporation extends DataClass implements Insertable<Corporation> {
  final int id;
  final String name;
  final String? ticker;
  final int? ceoId;
  final String? ceoName;
  final int? allianceId;
  final String? allianceName;
  final DateTime addedAt;
  const Corporation({
    required this.id,
    required this.name,
    this.ticker,
    this.ceoId,
    this.ceoName,
    this.allianceId,
    this.allianceName,
    required this.addedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    map['name'] = Variable<String>(name);
    if (!nullToAbsent || ticker != null) {
      map['ticker'] = Variable<String>(ticker);
    }
    if (!nullToAbsent || ceoId != null) {
      map['ceo_id'] = Variable<int>(ceoId);
    }
    if (!nullToAbsent || ceoName != null) {
      map['ceo_name'] = Variable<String>(ceoName);
    }
    if (!nullToAbsent || allianceId != null) {
      map['alliance_id'] = Variable<int>(allianceId);
    }
    if (!nullToAbsent || allianceName != null) {
      map['alliance_name'] = Variable<String>(allianceName);
    }
    map['added_at'] = Variable<DateTime>(addedAt);
    return map;
  }

  CorporationsCompanion toCompanion(bool nullToAbsent) {
    return CorporationsCompanion(
      id: Value(id),
      name: Value(name),
      ticker: ticker == null && nullToAbsent
          ? const Value.absent()
          : Value(ticker),
      ceoId: ceoId == null && nullToAbsent
          ? const Value.absent()
          : Value(ceoId),
      ceoName: ceoName == null && nullToAbsent
          ? const Value.absent()
          : Value(ceoName),
      allianceId: allianceId == null && nullToAbsent
          ? const Value.absent()
          : Value(allianceId),
      allianceName: allianceName == null && nullToAbsent
          ? const Value.absent()
          : Value(allianceName),
      addedAt: Value(addedAt),
    );
  }

  factory Corporation.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return Corporation(
      id: serializer.fromJson<int>(json['id']),
      name: serializer.fromJson<String>(json['name']),
      ticker: serializer.fromJson<String?>(json['ticker']),
      ceoId: serializer.fromJson<int?>(json['ceoId']),
      ceoName: serializer.fromJson<String?>(json['ceoName']),
      allianceId: serializer.fromJson<int?>(json['allianceId']),
      allianceName: serializer.fromJson<String?>(json['allianceName']),
      addedAt: serializer.fromJson<DateTime>(json['addedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'name': serializer.toJson<String>(name),
      'ticker': serializer.toJson<String?>(ticker),
      'ceoId': serializer.toJson<int?>(ceoId),
      'ceoName': serializer.toJson<String?>(ceoName),
      'allianceId': serializer.toJson<int?>(allianceId),
      'allianceName': serializer.toJson<String?>(allianceName),
      'addedAt': serializer.toJson<DateTime>(addedAt),
    };
  }

  Corporation copyWith({
    int? id,
    String? name,
    Value<String?> ticker = const Value.absent(),
    Value<int?> ceoId = const Value.absent(),
    Value<String?> ceoName = const Value.absent(),
    Value<int?> allianceId = const Value.absent(),
    Value<String?> allianceName = const Value.absent(),
    DateTime? addedAt,
  }) => Corporation(
    id: id ?? this.id,
    name: name ?? this.name,
    ticker: ticker.present ? ticker.value : this.ticker,
    ceoId: ceoId.present ? ceoId.value : this.ceoId,
    ceoName: ceoName.present ? ceoName.value : this.ceoName,
    allianceId: allianceId.present ? allianceId.value : this.allianceId,
    allianceName: allianceName.present ? allianceName.value : this.allianceName,
    addedAt: addedAt ?? this.addedAt,
  );
  Corporation copyWithCompanion(CorporationsCompanion data) {
    return Corporation(
      id: data.id.present ? data.id.value : this.id,
      name: data.name.present ? data.name.value : this.name,
      ticker: data.ticker.present ? data.ticker.value : this.ticker,
      ceoId: data.ceoId.present ? data.ceoId.value : this.ceoId,
      ceoName: data.ceoName.present ? data.ceoName.value : this.ceoName,
      allianceId: data.allianceId.present
          ? data.allianceId.value
          : this.allianceId,
      allianceName: data.allianceName.present
          ? data.allianceName.value
          : this.allianceName,
      addedAt: data.addedAt.present ? data.addedAt.value : this.addedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('Corporation(')
          ..write('id: $id, ')
          ..write('name: $name, ')
          ..write('ticker: $ticker, ')
          ..write('ceoId: $ceoId, ')
          ..write('ceoName: $ceoName, ')
          ..write('allianceId: $allianceId, ')
          ..write('allianceName: $allianceName, ')
          ..write('addedAt: $addedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    name,
    ticker,
    ceoId,
    ceoName,
    allianceId,
    allianceName,
    addedAt,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is Corporation &&
          other.id == this.id &&
          other.name == this.name &&
          other.ticker == this.ticker &&
          other.ceoId == this.ceoId &&
          other.ceoName == this.ceoName &&
          other.allianceId == this.allianceId &&
          other.allianceName == this.allianceName &&
          other.addedAt == this.addedAt);
}

class CorporationsCompanion extends UpdateCompanion<Corporation> {
  final Value<int> id;
  final Value<String> name;
  final Value<String?> ticker;
  final Value<int?> ceoId;
  final Value<String?> ceoName;
  final Value<int?> allianceId;
  final Value<String?> allianceName;
  final Value<DateTime> addedAt;
  const CorporationsCompanion({
    this.id = const Value.absent(),
    this.name = const Value.absent(),
    this.ticker = const Value.absent(),
    this.ceoId = const Value.absent(),
    this.ceoName = const Value.absent(),
    this.allianceId = const Value.absent(),
    this.allianceName = const Value.absent(),
    this.addedAt = const Value.absent(),
  });
  CorporationsCompanion.insert({
    this.id = const Value.absent(),
    required String name,
    this.ticker = const Value.absent(),
    this.ceoId = const Value.absent(),
    this.ceoName = const Value.absent(),
    this.allianceId = const Value.absent(),
    this.allianceName = const Value.absent(),
    required DateTime addedAt,
  }) : name = Value(name),
       addedAt = Value(addedAt);
  static Insertable<Corporation> custom({
    Expression<int>? id,
    Expression<String>? name,
    Expression<String>? ticker,
    Expression<int>? ceoId,
    Expression<String>? ceoName,
    Expression<int>? allianceId,
    Expression<String>? allianceName,
    Expression<DateTime>? addedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (name != null) 'name': name,
      if (ticker != null) 'ticker': ticker,
      if (ceoId != null) 'ceo_id': ceoId,
      if (ceoName != null) 'ceo_name': ceoName,
      if (allianceId != null) 'alliance_id': allianceId,
      if (allianceName != null) 'alliance_name': allianceName,
      if (addedAt != null) 'added_at': addedAt,
    });
  }

  CorporationsCompanion copyWith({
    Value<int>? id,
    Value<String>? name,
    Value<String?>? ticker,
    Value<int?>? ceoId,
    Value<String?>? ceoName,
    Value<int?>? allianceId,
    Value<String?>? allianceName,
    Value<DateTime>? addedAt,
  }) {
    return CorporationsCompanion(
      id: id ?? this.id,
      name: name ?? this.name,
      ticker: ticker ?? this.ticker,
      ceoId: ceoId ?? this.ceoId,
      ceoName: ceoName ?? this.ceoName,
      allianceId: allianceId ?? this.allianceId,
      allianceName: allianceName ?? this.allianceName,
      addedAt: addedAt ?? this.addedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (name.present) {
      map['name'] = Variable<String>(name.value);
    }
    if (ticker.present) {
      map['ticker'] = Variable<String>(ticker.value);
    }
    if (ceoId.present) {
      map['ceo_id'] = Variable<int>(ceoId.value);
    }
    if (ceoName.present) {
      map['ceo_name'] = Variable<String>(ceoName.value);
    }
    if (allianceId.present) {
      map['alliance_id'] = Variable<int>(allianceId.value);
    }
    if (allianceName.present) {
      map['alliance_name'] = Variable<String>(allianceName.value);
    }
    if (addedAt.present) {
      map['added_at'] = Variable<DateTime>(addedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('CorporationsCompanion(')
          ..write('id: $id, ')
          ..write('name: $name, ')
          ..write('ticker: $ticker, ')
          ..write('ceoId: $ceoId, ')
          ..write('ceoName: $ceoName, ')
          ..write('allianceId: $allianceId, ')
          ..write('allianceName: $allianceName, ')
          ..write('addedAt: $addedAt')
          ..write(')'))
        .toString();
  }
}

abstract class _$AppDatabase extends GeneratedDatabase {
  _$AppDatabase(QueryExecutor e) : super(e);
  $AppDatabaseManager get managers => $AppDatabaseManager(this);
  late final $CharactersTable characters = $CharactersTable(this);
  late final $EsiCacheTable esiCache = $EsiCacheTable(this);
  late final $AppSettingsTable appSettings = $AppSettingsTable(this);
  late final $PriceAlertsTable priceAlerts = $PriceAlertsTable(this);
  late final $CorporationsTable corporations = $CorporationsTable(this);
  @override
  Iterable<TableInfo<Table, Object?>> get allTables =>
      allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    characters,
    esiCache,
    appSettings,
    priceAlerts,
    corporations,
  ];
}

typedef $$CharactersTableCreateCompanionBuilder =
    CharactersCompanion Function({
      Value<int> id,
      required String name,
      Value<int?> corporationId,
      Value<String?> corporationName,
      Value<String?> portraitUrl,
      required DateTime addedAt,
    });
typedef $$CharactersTableUpdateCompanionBuilder =
    CharactersCompanion Function({
      Value<int> id,
      Value<String> name,
      Value<int?> corporationId,
      Value<String?> corporationName,
      Value<String?> portraitUrl,
      Value<DateTime> addedAt,
    });

class $$CharactersTableFilterComposer
    extends Composer<_$AppDatabase, $CharactersTable> {
  $$CharactersTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get name => $composableBuilder(
    column: $table.name,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get corporationId => $composableBuilder(
    column: $table.corporationId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get corporationName => $composableBuilder(
    column: $table.corporationName,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get portraitUrl => $composableBuilder(
    column: $table.portraitUrl,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get addedAt => $composableBuilder(
    column: $table.addedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$CharactersTableOrderingComposer
    extends Composer<_$AppDatabase, $CharactersTable> {
  $$CharactersTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get name => $composableBuilder(
    column: $table.name,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get corporationId => $composableBuilder(
    column: $table.corporationId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get corporationName => $composableBuilder(
    column: $table.corporationName,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get portraitUrl => $composableBuilder(
    column: $table.portraitUrl,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get addedAt => $composableBuilder(
    column: $table.addedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$CharactersTableAnnotationComposer
    extends Composer<_$AppDatabase, $CharactersTable> {
  $$CharactersTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get name =>
      $composableBuilder(column: $table.name, builder: (column) => column);

  GeneratedColumn<int> get corporationId => $composableBuilder(
    column: $table.corporationId,
    builder: (column) => column,
  );

  GeneratedColumn<String> get corporationName => $composableBuilder(
    column: $table.corporationName,
    builder: (column) => column,
  );

  GeneratedColumn<String> get portraitUrl => $composableBuilder(
    column: $table.portraitUrl,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get addedAt =>
      $composableBuilder(column: $table.addedAt, builder: (column) => column);
}

class $$CharactersTableTableManager
    extends
        RootTableManager<
          _$AppDatabase,
          $CharactersTable,
          Character,
          $$CharactersTableFilterComposer,
          $$CharactersTableOrderingComposer,
          $$CharactersTableAnnotationComposer,
          $$CharactersTableCreateCompanionBuilder,
          $$CharactersTableUpdateCompanionBuilder,
          (
            Character,
            BaseReferences<_$AppDatabase, $CharactersTable, Character>,
          ),
          Character,
          PrefetchHooks Function()
        > {
  $$CharactersTableTableManager(_$AppDatabase db, $CharactersTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$CharactersTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$CharactersTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$CharactersTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String> name = const Value.absent(),
                Value<int?> corporationId = const Value.absent(),
                Value<String?> corporationName = const Value.absent(),
                Value<String?> portraitUrl = const Value.absent(),
                Value<DateTime> addedAt = const Value.absent(),
              }) => CharactersCompanion(
                id: id,
                name: name,
                corporationId: corporationId,
                corporationName: corporationName,
                portraitUrl: portraitUrl,
                addedAt: addedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                required String name,
                Value<int?> corporationId = const Value.absent(),
                Value<String?> corporationName = const Value.absent(),
                Value<String?> portraitUrl = const Value.absent(),
                required DateTime addedAt,
              }) => CharactersCompanion.insert(
                id: id,
                name: name,
                corporationId: corporationId,
                corporationName: corporationName,
                portraitUrl: portraitUrl,
                addedAt: addedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$CharactersTableProcessedTableManager =
    ProcessedTableManager<
      _$AppDatabase,
      $CharactersTable,
      Character,
      $$CharactersTableFilterComposer,
      $$CharactersTableOrderingComposer,
      $$CharactersTableAnnotationComposer,
      $$CharactersTableCreateCompanionBuilder,
      $$CharactersTableUpdateCompanionBuilder,
      (Character, BaseReferences<_$AppDatabase, $CharactersTable, Character>),
      Character,
      PrefetchHooks Function()
    >;
typedef $$EsiCacheTableCreateCompanionBuilder =
    EsiCacheCompanion Function({
      required String url,
      Value<String?> etag,
      required DateTime expiresAt,
      required DateTime cachedAt,
      required String body,
      Value<int> rowid,
    });
typedef $$EsiCacheTableUpdateCompanionBuilder =
    EsiCacheCompanion Function({
      Value<String> url,
      Value<String?> etag,
      Value<DateTime> expiresAt,
      Value<DateTime> cachedAt,
      Value<String> body,
      Value<int> rowid,
    });

class $$EsiCacheTableFilterComposer
    extends Composer<_$AppDatabase, $EsiCacheTable> {
  $$EsiCacheTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get url => $composableBuilder(
    column: $table.url,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get etag => $composableBuilder(
    column: $table.etag,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get expiresAt => $composableBuilder(
    column: $table.expiresAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get cachedAt => $composableBuilder(
    column: $table.cachedAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get body => $composableBuilder(
    column: $table.body,
    builder: (column) => ColumnFilters(column),
  );
}

class $$EsiCacheTableOrderingComposer
    extends Composer<_$AppDatabase, $EsiCacheTable> {
  $$EsiCacheTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get url => $composableBuilder(
    column: $table.url,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get etag => $composableBuilder(
    column: $table.etag,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get expiresAt => $composableBuilder(
    column: $table.expiresAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get cachedAt => $composableBuilder(
    column: $table.cachedAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get body => $composableBuilder(
    column: $table.body,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$EsiCacheTableAnnotationComposer
    extends Composer<_$AppDatabase, $EsiCacheTable> {
  $$EsiCacheTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get url =>
      $composableBuilder(column: $table.url, builder: (column) => column);

  GeneratedColumn<String> get etag =>
      $composableBuilder(column: $table.etag, builder: (column) => column);

  GeneratedColumn<DateTime> get expiresAt =>
      $composableBuilder(column: $table.expiresAt, builder: (column) => column);

  GeneratedColumn<DateTime> get cachedAt =>
      $composableBuilder(column: $table.cachedAt, builder: (column) => column);

  GeneratedColumn<String> get body =>
      $composableBuilder(column: $table.body, builder: (column) => column);
}

class $$EsiCacheTableTableManager
    extends
        RootTableManager<
          _$AppDatabase,
          $EsiCacheTable,
          EsiCacheEntry,
          $$EsiCacheTableFilterComposer,
          $$EsiCacheTableOrderingComposer,
          $$EsiCacheTableAnnotationComposer,
          $$EsiCacheTableCreateCompanionBuilder,
          $$EsiCacheTableUpdateCompanionBuilder,
          (
            EsiCacheEntry,
            BaseReferences<_$AppDatabase, $EsiCacheTable, EsiCacheEntry>,
          ),
          EsiCacheEntry,
          PrefetchHooks Function()
        > {
  $$EsiCacheTableTableManager(_$AppDatabase db, $EsiCacheTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$EsiCacheTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$EsiCacheTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$EsiCacheTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> url = const Value.absent(),
                Value<String?> etag = const Value.absent(),
                Value<DateTime> expiresAt = const Value.absent(),
                Value<DateTime> cachedAt = const Value.absent(),
                Value<String> body = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => EsiCacheCompanion(
                url: url,
                etag: etag,
                expiresAt: expiresAt,
                cachedAt: cachedAt,
                body: body,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String url,
                Value<String?> etag = const Value.absent(),
                required DateTime expiresAt,
                required DateTime cachedAt,
                required String body,
                Value<int> rowid = const Value.absent(),
              }) => EsiCacheCompanion.insert(
                url: url,
                etag: etag,
                expiresAt: expiresAt,
                cachedAt: cachedAt,
                body: body,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$EsiCacheTableProcessedTableManager =
    ProcessedTableManager<
      _$AppDatabase,
      $EsiCacheTable,
      EsiCacheEntry,
      $$EsiCacheTableFilterComposer,
      $$EsiCacheTableOrderingComposer,
      $$EsiCacheTableAnnotationComposer,
      $$EsiCacheTableCreateCompanionBuilder,
      $$EsiCacheTableUpdateCompanionBuilder,
      (
        EsiCacheEntry,
        BaseReferences<_$AppDatabase, $EsiCacheTable, EsiCacheEntry>,
      ),
      EsiCacheEntry,
      PrefetchHooks Function()
    >;
typedef $$AppSettingsTableCreateCompanionBuilder =
    AppSettingsCompanion Function({
      required String key,
      required String value,
      Value<int> rowid,
    });
typedef $$AppSettingsTableUpdateCompanionBuilder =
    AppSettingsCompanion Function({
      Value<String> key,
      Value<String> value,
      Value<int> rowid,
    });

class $$AppSettingsTableFilterComposer
    extends Composer<_$AppDatabase, $AppSettingsTable> {
  $$AppSettingsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get key => $composableBuilder(
    column: $table.key,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnFilters(column),
  );
}

class $$AppSettingsTableOrderingComposer
    extends Composer<_$AppDatabase, $AppSettingsTable> {
  $$AppSettingsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get key => $composableBuilder(
    column: $table.key,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$AppSettingsTableAnnotationComposer
    extends Composer<_$AppDatabase, $AppSettingsTable> {
  $$AppSettingsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get key =>
      $composableBuilder(column: $table.key, builder: (column) => column);

  GeneratedColumn<String> get value =>
      $composableBuilder(column: $table.value, builder: (column) => column);
}

class $$AppSettingsTableTableManager
    extends
        RootTableManager<
          _$AppDatabase,
          $AppSettingsTable,
          AppSetting,
          $$AppSettingsTableFilterComposer,
          $$AppSettingsTableOrderingComposer,
          $$AppSettingsTableAnnotationComposer,
          $$AppSettingsTableCreateCompanionBuilder,
          $$AppSettingsTableUpdateCompanionBuilder,
          (
            AppSetting,
            BaseReferences<_$AppDatabase, $AppSettingsTable, AppSetting>,
          ),
          AppSetting,
          PrefetchHooks Function()
        > {
  $$AppSettingsTableTableManager(_$AppDatabase db, $AppSettingsTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$AppSettingsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$AppSettingsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$AppSettingsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> key = const Value.absent(),
                Value<String> value = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => AppSettingsCompanion(key: key, value: value, rowid: rowid),
          createCompanionCallback:
              ({
                required String key,
                required String value,
                Value<int> rowid = const Value.absent(),
              }) => AppSettingsCompanion.insert(
                key: key,
                value: value,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$AppSettingsTableProcessedTableManager =
    ProcessedTableManager<
      _$AppDatabase,
      $AppSettingsTable,
      AppSetting,
      $$AppSettingsTableFilterComposer,
      $$AppSettingsTableOrderingComposer,
      $$AppSettingsTableAnnotationComposer,
      $$AppSettingsTableCreateCompanionBuilder,
      $$AppSettingsTableUpdateCompanionBuilder,
      (
        AppSetting,
        BaseReferences<_$AppDatabase, $AppSettingsTable, AppSetting>,
      ),
      AppSetting,
      PrefetchHooks Function()
    >;
typedef $$PriceAlertsTableCreateCompanionBuilder =
    PriceAlertsCompanion Function({
      Value<int> id,
      required int typeId,
      required int regionId,
      required double targetPrice,
      required String condition,
      Value<bool> triggered,
      required DateTime createdAt,
    });
typedef $$PriceAlertsTableUpdateCompanionBuilder =
    PriceAlertsCompanion Function({
      Value<int> id,
      Value<int> typeId,
      Value<int> regionId,
      Value<double> targetPrice,
      Value<String> condition,
      Value<bool> triggered,
      Value<DateTime> createdAt,
    });

class $$PriceAlertsTableFilterComposer
    extends Composer<_$AppDatabase, $PriceAlertsTable> {
  $$PriceAlertsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get typeId => $composableBuilder(
    column: $table.typeId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get regionId => $composableBuilder(
    column: $table.regionId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<double> get targetPrice => $composableBuilder(
    column: $table.targetPrice,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get condition => $composableBuilder(
    column: $table.condition,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get triggered => $composableBuilder(
    column: $table.triggered,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get createdAt => $composableBuilder(
    column: $table.createdAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$PriceAlertsTableOrderingComposer
    extends Composer<_$AppDatabase, $PriceAlertsTable> {
  $$PriceAlertsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get typeId => $composableBuilder(
    column: $table.typeId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get regionId => $composableBuilder(
    column: $table.regionId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<double> get targetPrice => $composableBuilder(
    column: $table.targetPrice,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get condition => $composableBuilder(
    column: $table.condition,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get triggered => $composableBuilder(
    column: $table.triggered,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get createdAt => $composableBuilder(
    column: $table.createdAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$PriceAlertsTableAnnotationComposer
    extends Composer<_$AppDatabase, $PriceAlertsTable> {
  $$PriceAlertsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<int> get typeId =>
      $composableBuilder(column: $table.typeId, builder: (column) => column);

  GeneratedColumn<int> get regionId =>
      $composableBuilder(column: $table.regionId, builder: (column) => column);

  GeneratedColumn<double> get targetPrice => $composableBuilder(
    column: $table.targetPrice,
    builder: (column) => column,
  );

  GeneratedColumn<String> get condition =>
      $composableBuilder(column: $table.condition, builder: (column) => column);

  GeneratedColumn<bool> get triggered =>
      $composableBuilder(column: $table.triggered, builder: (column) => column);

  GeneratedColumn<DateTime> get createdAt =>
      $composableBuilder(column: $table.createdAt, builder: (column) => column);
}

class $$PriceAlertsTableTableManager
    extends
        RootTableManager<
          _$AppDatabase,
          $PriceAlertsTable,
          PriceAlert,
          $$PriceAlertsTableFilterComposer,
          $$PriceAlertsTableOrderingComposer,
          $$PriceAlertsTableAnnotationComposer,
          $$PriceAlertsTableCreateCompanionBuilder,
          $$PriceAlertsTableUpdateCompanionBuilder,
          (
            PriceAlert,
            BaseReferences<_$AppDatabase, $PriceAlertsTable, PriceAlert>,
          ),
          PriceAlert,
          PrefetchHooks Function()
        > {
  $$PriceAlertsTableTableManager(_$AppDatabase db, $PriceAlertsTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$PriceAlertsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$PriceAlertsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$PriceAlertsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<int> typeId = const Value.absent(),
                Value<int> regionId = const Value.absent(),
                Value<double> targetPrice = const Value.absent(),
                Value<String> condition = const Value.absent(),
                Value<bool> triggered = const Value.absent(),
                Value<DateTime> createdAt = const Value.absent(),
              }) => PriceAlertsCompanion(
                id: id,
                typeId: typeId,
                regionId: regionId,
                targetPrice: targetPrice,
                condition: condition,
                triggered: triggered,
                createdAt: createdAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                required int typeId,
                required int regionId,
                required double targetPrice,
                required String condition,
                Value<bool> triggered = const Value.absent(),
                required DateTime createdAt,
              }) => PriceAlertsCompanion.insert(
                id: id,
                typeId: typeId,
                regionId: regionId,
                targetPrice: targetPrice,
                condition: condition,
                triggered: triggered,
                createdAt: createdAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$PriceAlertsTableProcessedTableManager =
    ProcessedTableManager<
      _$AppDatabase,
      $PriceAlertsTable,
      PriceAlert,
      $$PriceAlertsTableFilterComposer,
      $$PriceAlertsTableOrderingComposer,
      $$PriceAlertsTableAnnotationComposer,
      $$PriceAlertsTableCreateCompanionBuilder,
      $$PriceAlertsTableUpdateCompanionBuilder,
      (
        PriceAlert,
        BaseReferences<_$AppDatabase, $PriceAlertsTable, PriceAlert>,
      ),
      PriceAlert,
      PrefetchHooks Function()
    >;
typedef $$CorporationsTableCreateCompanionBuilder =
    CorporationsCompanion Function({
      Value<int> id,
      required String name,
      Value<String?> ticker,
      Value<int?> ceoId,
      Value<String?> ceoName,
      Value<int?> allianceId,
      Value<String?> allianceName,
      required DateTime addedAt,
    });
typedef $$CorporationsTableUpdateCompanionBuilder =
    CorporationsCompanion Function({
      Value<int> id,
      Value<String> name,
      Value<String?> ticker,
      Value<int?> ceoId,
      Value<String?> ceoName,
      Value<int?> allianceId,
      Value<String?> allianceName,
      Value<DateTime> addedAt,
    });

class $$CorporationsTableFilterComposer
    extends Composer<_$AppDatabase, $CorporationsTable> {
  $$CorporationsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get name => $composableBuilder(
    column: $table.name,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get ticker => $composableBuilder(
    column: $table.ticker,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get ceoId => $composableBuilder(
    column: $table.ceoId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get ceoName => $composableBuilder(
    column: $table.ceoName,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get allianceId => $composableBuilder(
    column: $table.allianceId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get allianceName => $composableBuilder(
    column: $table.allianceName,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get addedAt => $composableBuilder(
    column: $table.addedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$CorporationsTableOrderingComposer
    extends Composer<_$AppDatabase, $CorporationsTable> {
  $$CorporationsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get name => $composableBuilder(
    column: $table.name,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get ticker => $composableBuilder(
    column: $table.ticker,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get ceoId => $composableBuilder(
    column: $table.ceoId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get ceoName => $composableBuilder(
    column: $table.ceoName,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get allianceId => $composableBuilder(
    column: $table.allianceId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get allianceName => $composableBuilder(
    column: $table.allianceName,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get addedAt => $composableBuilder(
    column: $table.addedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$CorporationsTableAnnotationComposer
    extends Composer<_$AppDatabase, $CorporationsTable> {
  $$CorporationsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get name =>
      $composableBuilder(column: $table.name, builder: (column) => column);

  GeneratedColumn<String> get ticker =>
      $composableBuilder(column: $table.ticker, builder: (column) => column);

  GeneratedColumn<int> get ceoId =>
      $composableBuilder(column: $table.ceoId, builder: (column) => column);

  GeneratedColumn<String> get ceoName =>
      $composableBuilder(column: $table.ceoName, builder: (column) => column);

  GeneratedColumn<int> get allianceId => $composableBuilder(
    column: $table.allianceId,
    builder: (column) => column,
  );

  GeneratedColumn<String> get allianceName => $composableBuilder(
    column: $table.allianceName,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get addedAt =>
      $composableBuilder(column: $table.addedAt, builder: (column) => column);
}

class $$CorporationsTableTableManager
    extends
        RootTableManager<
          _$AppDatabase,
          $CorporationsTable,
          Corporation,
          $$CorporationsTableFilterComposer,
          $$CorporationsTableOrderingComposer,
          $$CorporationsTableAnnotationComposer,
          $$CorporationsTableCreateCompanionBuilder,
          $$CorporationsTableUpdateCompanionBuilder,
          (
            Corporation,
            BaseReferences<_$AppDatabase, $CorporationsTable, Corporation>,
          ),
          Corporation,
          PrefetchHooks Function()
        > {
  $$CorporationsTableTableManager(_$AppDatabase db, $CorporationsTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$CorporationsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$CorporationsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$CorporationsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String> name = const Value.absent(),
                Value<String?> ticker = const Value.absent(),
                Value<int?> ceoId = const Value.absent(),
                Value<String?> ceoName = const Value.absent(),
                Value<int?> allianceId = const Value.absent(),
                Value<String?> allianceName = const Value.absent(),
                Value<DateTime> addedAt = const Value.absent(),
              }) => CorporationsCompanion(
                id: id,
                name: name,
                ticker: ticker,
                ceoId: ceoId,
                ceoName: ceoName,
                allianceId: allianceId,
                allianceName: allianceName,
                addedAt: addedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                required String name,
                Value<String?> ticker = const Value.absent(),
                Value<int?> ceoId = const Value.absent(),
                Value<String?> ceoName = const Value.absent(),
                Value<int?> allianceId = const Value.absent(),
                Value<String?> allianceName = const Value.absent(),
                required DateTime addedAt,
              }) => CorporationsCompanion.insert(
                id: id,
                name: name,
                ticker: ticker,
                ceoId: ceoId,
                ceoName: ceoName,
                allianceId: allianceId,
                allianceName: allianceName,
                addedAt: addedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$CorporationsTableProcessedTableManager =
    ProcessedTableManager<
      _$AppDatabase,
      $CorporationsTable,
      Corporation,
      $$CorporationsTableFilterComposer,
      $$CorporationsTableOrderingComposer,
      $$CorporationsTableAnnotationComposer,
      $$CorporationsTableCreateCompanionBuilder,
      $$CorporationsTableUpdateCompanionBuilder,
      (
        Corporation,
        BaseReferences<_$AppDatabase, $CorporationsTable, Corporation>,
      ),
      Corporation,
      PrefetchHooks Function()
    >;

class $AppDatabaseManager {
  final _$AppDatabase _db;
  $AppDatabaseManager(this._db);
  $$CharactersTableTableManager get characters =>
      $$CharactersTableTableManager(_db, _db.characters);
  $$EsiCacheTableTableManager get esiCache =>
      $$EsiCacheTableTableManager(_db, _db.esiCache);
  $$AppSettingsTableTableManager get appSettings =>
      $$AppSettingsTableTableManager(_db, _db.appSettings);
  $$PriceAlertsTableTableManager get priceAlerts =>
      $$PriceAlertsTableTableManager(_db, _db.priceAlerts);
  $$CorporationsTableTableManager get corporations =>
      $$CorporationsTableTableManager(_db, _db.corporations);
}
