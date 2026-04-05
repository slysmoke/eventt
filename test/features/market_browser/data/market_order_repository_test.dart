import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eventt/core/esi/esi_client.dart';
import 'package:eventt/features/market_browser/data/market_order_repository.dart';

class _MockEsiClient extends Mock implements EsiClient {}

void main() {
  late _MockEsiClient esi;
  late MarketOrderRepository repo;

  setUp(() {
    esi = _MockEsiClient();
    repo = MarketOrderRepository(esi: esi);
    registerFallbackValue(<String, dynamic>{});
  });

  const regionId = 10000002; // The Forge
  const typeId = 34; // Tritanium

  group('MarketOrderRepository.fetchOrders()', () {
    test('returns sell and buy orders parsed from ESI', () async {
      when(() => esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'type_id': typeId, 'order_type': 'all'},
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: [
              {
                'order_id': 100,
                'is_buy_order': false,
                'price': 5.5,
                'volume_remain': 1000,
                'volume_total': 2000,
                'location_id': 60004588,
                'system_id': 30000142,
                'type_id': 34,
              },
              {
                'order_id': 101,
                'is_buy_order': true,
                'price': 4.8,
                'volume_remain': 500,
                'volume_total': 500,
                'location_id': 60004588,
                'system_id': 30000142,
                'type_id': 34,
              },
            ],
          ));

      final orders = await repo.fetchOrders(regionId, typeId);

      expect(orders.length, 2);
      expect(orders.where((o) => !o.isBuyOrder).length, 1);
      expect(orders.where((o) => o.isBuyOrder).length, 1);
      expect(orders.first.price, 5.5);
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

      final orders = await repo.fetchOrders(regionId, typeId);
      expect(orders, isEmpty);
    });

    test('sell orders sorted ascending by price', () async {
      when(() => esi.get(any(),
              queryParameters: any(named: 'queryParameters')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: ''),
                statusCode: 200,
                data: [
                  _order(id: 1, buy: false, price: 10.0),
                  _order(id: 2, buy: false, price: 5.0),
                  _order(id: 3, buy: false, price: 7.5),
                ],
              ));

      final orders = await repo.fetchOrders(regionId, typeId);
      final sells = orders.where((o) => !o.isBuyOrder).toList();

      expect(sells[0].price, 5.0);
      expect(sells[1].price, 7.5);
      expect(sells[2].price, 10.0);
    });

    test('buy orders sorted descending by price', () async {
      when(() => esi.get(any(),
              queryParameters: any(named: 'queryParameters')))
          .thenAnswer((_) async => Response(
                requestOptions: RequestOptions(path: ''),
                statusCode: 200,
                data: [
                  _order(id: 1, buy: true, price: 4.0),
                  _order(id: 2, buy: true, price: 8.0),
                  _order(id: 3, buy: true, price: 6.0),
                ],
              ));

      final orders = await repo.fetchOrders(regionId, typeId);
      final buys = orders.where((o) => o.isBuyOrder).toList();

      expect(buys[0].price, 8.0);
      expect(buys[1].price, 6.0);
      expect(buys[2].price, 4.0);
    });
  });
}

Map<String, dynamic> _order({
  required int id,
  required bool buy,
  required double price,
}) =>
    {
      'order_id': id,
      'is_buy_order': buy,
      'price': price,
      'volume_remain': 100,
      'volume_total': 100,
      'location_id': 60004588,
      'system_id': 30000142,
      'type_id': 34,
    };
