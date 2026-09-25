package net.osslabz.mexc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Exchange;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.crypto.OhlcAsset;
import net.osslabz.crypto.Order;
import net.osslabz.crypto.OrderAction;
import net.osslabz.crypto.OrderStatus;
import net.osslabz.crypto.OrderType;
import net.osslabz.crypto.TradingAsset;
import net.osslabz.mexc.client.ws.dto.raw.RawOhlc;
import net.osslabz.mexc.client.ws.dto.raw.RawOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MexcMapperTest {

    private static final CurrencyPair BTC_USDT = new CurrencyPair("BTC", "USDT");

    private final MexcMapper mapper = new MexcMapper();

    @ParameterizedTest
    @CsvSource({"PT1M,Min1", "PT5M,Min5", "PT15M,Min15", "PT30M,Min30", "PT4H,Hour4", "PT24H,Day1"})
    void mapIntervalReturnsTheMexcName(Interval interval, String expected) {
        assertEquals(expected, mapper.mapInterval(interval));
    }

    @Test
    void mapIntervalRejectsAnIntervalMexcDoesNotStream() {
        assertThrows(IllegalArgumentException.class, () -> mapper.mapInterval(Interval.PT1H));
    }

    @Test
    void mapSymbolConcatenatesBaseAndCounterCurrency() {
        assertEquals("BTCUSDT", mapper.mapSymbol(BTC_USDT));
    }

    @Test
    void subscriptionIdentifierNamesTheKlineChannel() {
        assertEquals(
                "spot@public.kline.v3.api@BTCUSDT@Min15", mapper.calcSubscriptionIdentifier(BTC_USDT, Interval.PT15M));
    }

    @Test
    void mapsAKlineToAnOhlcWithTheVolumeWeightedAveragePrice() {
        RawOhlc rawOhlc = rawOhlc(new BigDecimal("300"), new BigDecimal("4"));

        Ohlc ohlc = mapper.map(BTC_USDT, Interval.PT1M, rawOhlc);

        assertEquals(new OhlcAsset(new TradingAsset(Exchange.MEXC, BTC_USDT), Interval.PT1M), ohlc.getAsset());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:13:20.123Z[UTC]"), ohlc.getUpdateTime());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:13Z[UTC]"), ohlc.getOpenTime());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:14Z[UTC]"), ohlc.getCloseTime());
        assertEquals(new BigDecimal("70"), ohlc.getOpenPrice());
        assertEquals(new BigDecimal("80"), ohlc.getHighPrice());
        assertEquals(new BigDecimal("60"), ohlc.getLowPrice());
        assertEquals(new BigDecimal("75"), ohlc.getClosePrice());
        assertEquals(new BigDecimal("300"), ohlc.getVolume());
        assertEquals(new BigDecimal("4"), ohlc.getQuantity());
        assertEquals(new BigDecimal("75.00000000"), ohlc.getAvgPrice());
    }

    @Test
    void usesTheClosePriceAsAveragePriceWhenNothingTraded() {
        RawOhlc rawOhlc = rawOhlc(BigDecimal.ZERO, BigDecimal.ZERO);

        Ohlc ohlc = mapper.map(BTC_USDT, Interval.PT1M, rawOhlc);

        assertEquals(new BigDecimal("75"), ohlc.getAvgPrice());
    }

    @Test
    void mapsAnOrderUpdate() {
        Order order = mapper.map(null, rawOrder("BTCUSDT", 2));

        assertEquals("order-1", order.getExchangeOrderId());
        assertEquals("client-1", order.getClientOrderId());
        assertEquals(new TradingAsset(Exchange.MEXC, BTC_USDT), order.getAsset());
        assertEquals(OrderAction.SELL, order.getAction());
        assertEquals(OrderType.LIMIT, order.getType());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("2"), order.getQuantity());
        assertEquals(new BigDecimal("1.5"), order.getCumulativeQuantity());
        assertEquals(new BigDecimal("150"), order.getAmount());
        assertEquals(new BigDecimal("112.5"), order.getCumulativeAmount());
        assertEquals(new BigDecimal("75"), order.getAvgPrice());
        assertEquals(new BigDecimal("76"), order.getPrice());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:13:20.123Z[UTC]"), order.getCreatedAt());
        assertEquals(ZonedDateTime.parse("2023-11-14T22:13:20.456Z[UTC]"), order.getUpdatedAt());
    }

    @ParameterizedTest
    @CsvSource({"1,NEW", "2,FILLED", "3,PARTIALLY_FILLED", "4,CANCELED", "5,PARTIALLY_CANCELED"})
    void mapsEveryOrderStatus(int status, OrderStatus expected) {
        assertEquals(expected, mapper.map(null, rawOrder("BTCUSDT", status)).getStatus());
    }

    @Test
    void mapsAMarketBuyOrder() {
        RawOrder rawOrder = rawOrder("BTCUSDT", 1);
        rawOrder.getData().setTradeType(5);
        rawOrder.getData().setType(1);

        Order order = mapper.map(null, rawOrder);

        assertEquals(OrderType.MARKET, order.getType());
        assertEquals(OrderAction.BUY, order.getAction());
    }

    @Test
    void mapOrderRejectsAnUnknownOrderType() {
        RawOrder rawOrder = rawOrder("BTCUSDT", 1);
        rawOrder.getData().setTradeType(3);

        assertThrows(UnsupportedOperationException.class, () -> mapper.map(null, rawOrder));
    }

    @Test
    void mapOrderRejectsAnUnknownSide() {
        RawOrder rawOrder = rawOrder("BTCUSDT", 1);
        rawOrder.getData().setType(3);

        assertThrows(IllegalArgumentException.class, () -> mapper.map(null, rawOrder));
    }

    @Test
    void mapOrderRejectsAnUnknownStatus() {
        RawOrder rawOrder = rawOrder("BTCUSDT", 9);

        assertThrows(UnsupportedOperationException.class, () -> mapper.map(null, rawOrder));
    }

    @Test
    void mapOrderRejectsAPairNotQuotedInUsdt() {
        RawOrder rawOrder = rawOrder("BTCUSDC", 2);

        assertThrows(IllegalArgumentException.class, () -> mapper.map(null, rawOrder));
    }

    @Test
    void mapOrderRejectsAMessageWithoutOrderData() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(null, new RawOrder()));
    }

    private static RawOrder rawOrder(String symbol, int status) {
        RawOrder.OrderData data = new RawOrder.OrderData();
        data.setOrderId("order-1");
        data.setClientOrderId("client-1");
        data.setType(2);
        data.setTradeType(1);
        data.setStatus(status);
        data.setQuantity(new BigDecimal("2"));
        data.setCumulativeQuantity(new BigDecimal("1.5"));
        data.setAmount(new BigDecimal("150"));
        data.setCumulativeAmount(new BigDecimal("112.5"));
        data.setAvgPrice(new BigDecimal("75"));
        data.setPrice(new BigDecimal("76"));
        data.setCreateTime(1_700_000_000_123L);
        RawOrder rawOrder = new RawOrder();
        rawOrder.setSymbol(symbol);
        rawOrder.setTime(1_700_000_000_456L);
        rawOrder.setData(data);
        return rawOrder;
    }

    private static RawOhlc rawOhlc(BigDecimal volume, BigDecimal quantity) {
        RawOhlc rawOhlc = new RawOhlc();
        rawOhlc.setTime(1_700_000_000_123L);
        RawOhlc.OhlData data = rawOhlc.new OhlData();
        RawOhlc.OhlData.OhlcContent content = data.new OhlcContent();
        content.setOpenTime(1_699_999_980L);
        content.setCloseTime(1_700_000_040L);
        content.setOpenPrice(new BigDecimal("70"));
        content.setHighPrice(new BigDecimal("80"));
        content.setLowPrice(new BigDecimal("60"));
        content.setClosePrice(new BigDecimal("75"));
        content.setVolume(volume);
        content.setQuantity(quantity);
        data.setContent(content);
        rawOhlc.setData(data);
        return rawOhlc;
    }
}
