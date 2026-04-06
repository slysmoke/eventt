import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eve_ntt/core/esi/esi_client.dart';
import 'package:eve_ntt/features/market_analysis/data/region_orders_repository.dart';

class _MockEsiClient extends Mock implements EsiClient {}

void main() {
  late _MockEsiClient esi;
  late RegionOrdersRepository repo;

  setUp(() {
    esi = _MockEsiClient();
    repo = RegionOrdersRepository(esi: esi);
    registerFallbackValue(<String, dynamic>{});
  });

  const regionId = 10000002;

  group('RegionOrdersRepository.fetchAllSellOrders()', () {
    test('single page fetch returns parsed orders', () async {
      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 1},
          )).thenAnswer((_) async => _response(
            data: [_order(34, 100.0), _order(35, 200.0)],
            xPages: 1,
          ));

      final orders = await repo.fetchAllSellOrders(regionId);

      expect(orders.length, 2);
      expect(orders.map((o) => o.typeId).toSet(), {34, 35});
    });

    test('multi-page fetch retrieves all pages', () async {
      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 1},
          )).thenAnswer((_) async => _response(
            data: [_order(34, 100.0)],
            xPages: 3,
          ));

      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 2},
          )).thenAnswer((_) async => _response(
            data: [_order(35, 200.0)],
            xPages: 3,
          ));

      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 3},
          )).thenAnswer((_) async => _response(
            data: [_order(36, 300.0)],
            xPages: 3,
          ));

      final orders = await repo.fetchAllSellOrders(regionId);

      expect(orders.length, 3);
      expect(orders.map((o) => o.typeId).toSet(), {34, 35, 36});
    });

    test('returns empty list on 4xx response', () async {
      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: any(named: 'queryParameters'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 404,
            data: null,
          ));

      final orders = await repo.fetchAllSellOrders(regionId);
      expect(orders, isEmpty);
    });

    test('calls onProgress callback', () async {
      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 1},
          )).thenAnswer((_) async => _response(
            data: [_order(34, 100.0)],
            xPages: 2,
          ));

      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': 2},
          )).thenAnswer((_) async => _response(
            data: [_order(35, 200.0)],
            xPages: 2,
          ));

      final progressCalls = <(int, int)>[];
      await repo.fetchAllSellOrders(
        regionId,
        onProgress: (done, total) => progressCalls.add((done, total)),
      );

      expect(progressCalls, isNotEmpty);
      // First call after page 1: done=1, total=2
      expect(progressCalls.first.$1, 1);
      expect(progressCalls.first.$2, 2);
    });
  });
}

Response<dynamic> _response({
  required List<Map<String, dynamic>> data,
  required int xPages,
}) =>
    Response(
      requestOptions: RequestOptions(path: ''),
      statusCode: 200,
      data: data,
      headers: Headers.fromMap({
        'x-pages': [xPages.toString()],
      }),
    );

Map<String, dynamic> _order(int typeId, double price) => {
      'order_id': typeId * 1000,
      'is_buy_order': false,
      'price': price,
      'volume_remain': 100,
      'volume_total': 100,
      'location_id': 60003760,
      'system_id': 30000142,
      'type_id': typeId,
    };
