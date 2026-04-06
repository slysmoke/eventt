import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eve_ntt/core/esi/esi_client.dart';
import 'package:eve_ntt/features/market_analysis/data/market_history_repository.dart';

class _MockEsiClient extends Mock implements EsiClient {}

void main() {
  late _MockEsiClient esi;
  late MarketHistoryRepository repo;

  setUp(() {
    esi = _MockEsiClient();
    repo = MarketHistoryRepository(esi: esi);
    registerFallbackValue(<String, dynamic>{});
  });

  const regionId = 10000002;
  const typeId = 34;

  group('MarketHistoryRepository.fetchHistory()', () {
    test('parses history entries from ESI', () async {
      when(() => esi.get(
            '/markets/$regionId/history/',
            queryParameters: {'type_id': typeId},
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: [
              {
                'date': '2024-01-01',
                'average': 5.5,
                'highest': 6.0,
                'lowest': 5.0,
                'order_count': 100,
                'volume': 50000,
              },
              {
                'date': '2024-01-02',
                'average': 5.8,
                'highest': 6.2,
                'lowest': 5.3,
                'order_count': 120,
                'volume': 60000,
              },
            ],
          ));

      final history = await repo.fetchHistory(regionId, typeId);

      expect(history.length, 2);
      expect(history.first.date, DateTime.utc(2024, 1, 1));
      expect(history.first.average, 5.5);
      expect(history.first.highest, 6.0);
      expect(history.first.lowest, 5.0);
      expect(history.first.orderCount, 100);
      expect(history.first.volume, 50000);
    });

    test('returns entries sorted by date ascending', () async {
      when(() => esi.get(any(),
              queryParameters: any(named: 'queryParameters')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: ''),
                statusCode: 200,
                data: [
                  _entry('2024-01-03', 7.0),
                  _entry('2024-01-01', 5.0),
                  _entry('2024-01-02', 6.0),
                ],
              ));

      final history = await repo.fetchHistory(regionId, typeId);

      expect(history[0].date, DateTime.utc(2024, 1, 1));
      expect(history[1].date, DateTime.utc(2024, 1, 2));
      expect(history[2].date, DateTime.utc(2024, 1, 3));
    });

    test('returns empty list on 4xx response', () async {
      when(() => esi.get(any(),
              queryParameters: any(named: 'queryParameters')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: ''),
                statusCode: 404,
                data: null,
              ));

      final history = await repo.fetchHistory(regionId, typeId);
      expect(history, isEmpty);
    });

    test('PLEX (44992) always uses region 19000001', () async {
      const plexId = 44992;
      const newEdenRegion = 19000001;

      when(() => esi.get(
            '/markets/$newEdenRegion/history/',
            queryParameters: {'type_id': plexId},
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: [_entry('2024-01-01', 1500000000.0)],
          ));

      final history = await repo.fetchHistory(10000002, plexId);
      expect(history.length, 1);
      expect(history.first.average, 1500000000.0);
    });
  });
}

Map<String, dynamic> _entry(String date, double avg) => {
      'date': date,
      'average': avg,
      'highest': avg + 0.5,
      'lowest': avg - 0.5,
      'order_count': 10,
      'volume': 1000,
    };
