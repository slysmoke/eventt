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

abstract class _$AppDatabase extends GeneratedDatabase {
  _$AppDatabase(QueryExecutor e) : super(e);
  $AppDatabaseManager get managers => $AppDatabaseManager(this);
  late final $CharactersTable characters = $CharactersTable(this);
  late final $EsiCacheTable esiCache = $EsiCacheTable(this);
  late final $AppSettingsTable appSettings = $AppSettingsTable(this);
  @override
  Iterable<TableInfo<Table, Object?>> get allTables =>
      allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    characters,
    esiCache,
    appSettings,
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

class $AppDatabaseManager {
  final _$AppDatabase _db;
  $AppDatabaseManager(this._db);
  $$CharactersTableTableManager get characters =>
      $$CharactersTableTableManager(_db, _db.characters);
  $$EsiCacheTableTableManager get esiCache =>
      $$EsiCacheTableTableManager(_db, _db.esiCache);
  $$AppSettingsTableTableManager get appSettings =>
      $$AppSettingsTableTableManager(_db, _db.appSettings);
}
