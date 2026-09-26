package net.osslabz.mexc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import net.osslabz.mexc.proto.PrivateOrdersV3Api;
import net.osslabz.mexc.proto.PublicSpotKlineV3Api;
import net.osslabz.mexc.proto.PushDataV3ApiWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MexcMapperTest {

    private static final CurrencyPair BTC_USDT = new CurrencyPair("BTC", "USDT");

    private final MexcMapper mapper = new MexcMapper();

    @ParameterizedTest
    @CsvSource({"PT1M,Min1", "PT5M,Min5", "PT15M,Min15", "PT30M,Min30", "PT1H,Min60", "PT4H,Hour4", "PT24H,Day1"})
    void mapIntervalReturnsTheMexcName(Interval interval, String expected) {
        assertEquals(expected, mapper.mapInterval(interval));
    }

    @Test
    void mapIntervalRejectsAnIntervalMexcDoesNotStream() {
        assertThrows(IllegalArgumentException.class, () -> mapper.mapInterval(Interval.PT12H));
    }

    @Test
    void mapSymbolConcatenatesBaseAndCounterCurrency() {
        assertEquals("BTCUSDT", mapper.mapSymbol(BTC_USDT));
    }

    @Test
    void subscriptionIdentifierNamesTheProtobufKlineChannel() {
        assertEquals(
                "spot@public.kline.v3.api.pb@BTCUSDT@Min15",
                mapper.calcSubscriptionIdentifier(BTC_USDT, Interval.PT15M));
    }

    @Test
    void mapsAKlineToAnOhlcWithTheVolumeWeightedAveragePrice() {
        Ohlc ohlc = mapper.map(BTC_USDT, Interval.PT1M, klinePush("4", "300"));

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
        Ohlc ohlc = mapper.map(BTC_USDT, Interval.PT1M, klinePush("0", "0"));

        assertEquals(new BigDecimal("75"), ohlc.getAvgPrice());
    }

    @Test
    void mapsAnOrderUpdate() {
        Order order = mapper.map(orderPush("BTCUSDT", 2));

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

    @Test
    void takesTheUpdateTimeFromTheCreateTimeOfThePushWhenItHasOne() {
        PushDataV3ApiWrapper push = orderPush("BTCUSDT", 2).toBuilder()
                .setCreateTime(1_700_000_000_789L)
                .build();

        assertEquals(
                ZonedDateTime.parse("2023-11-14T22:13:20.789Z[UTC]"),
                mapper.map(push).getUpdatedAt());
    }

    @Test
    void mapsFieldsMexcLeavesOutToNull() {
        PushDataV3ApiWrapper push = orderPush("BTCUSDT", 2).toBuilder()
                .setPrivateOrders(order(2).clearClientId().clearAvgPrice())
                .build();

        Order order = mapper.map(push);

        assertNull(order.getClientOrderId());
        assertNull(order.getAvgPrice());
    }

    @ParameterizedTest
    @CsvSource({"1,NEW", "2,FILLED", "3,PARTIALLY_FILLED", "4,CANCELED", "5,PARTIALLY_CANCELED"})
    void mapsEveryOrderStatus(int status, OrderStatus expected) {
        assertEquals(expected, mapper.map(orderPush("BTCUSDT", status)).getStatus());
    }

    @Test
    void mapsAMarketBuyOrder() {
        Order order = mapper.map(orderPush(order(1).setOrderType(5).setTradeType(1)));

        assertEquals(OrderType.MARKET, order.getType());
        assertEquals(OrderAction.BUY, order.getAction());
    }

    @ParameterizedTest
    @CsvSource({
        "1,LIMIT",
        "2,POST_ONLY",
        "3,IMMEDIATE_OR_CANCEL",
        "4,FILL_OR_KILL",
        "5,MARKET",
        "100,STOP_LOSS_TAKE_PROFIT"
    })
    void mapsEveryOrderType(int orderType, OrderType expected) {
        assertEquals(
                expected,
                mapper.map(orderPush(order(1).setOrderType(orderType))).getType());
    }

    @Test
    void mapOrderRejectsAnUnknownOrderType() {
        PushDataV3ApiWrapper push = orderPush(order(1).setOrderType(6));

        assertThrows(UnsupportedOperationException.class, () -> mapper.map(push));
    }

    @Test
    void mapOrderRejectsAnUnknownSide() {
        PushDataV3ApiWrapper push = orderPush(order(1).setTradeType(3));

        assertThrows(IllegalArgumentException.class, () -> mapper.map(push));
    }

    @Test
    void mapOrderRejectsAnUnknownStatus() {
        PushDataV3ApiWrapper push = orderPush("BTCUSDT", 9);

        assertThrows(UnsupportedOperationException.class, () -> mapper.map(push));
    }

    @Test
    void mapOrderRejectsAPairNotQuotedInUsdt() {
        PushDataV3ApiWrapper push = orderPush("BTCUSDC", 2);

        assertThrows(IllegalArgumentException.class, () -> mapper.map(push));
    }

    @Test
    void mapOrderRejectsAPushWithoutAnOrder() {
        PushDataV3ApiWrapper push = klinePush("4", "300");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mapper.map(push));
        assertEquals("The push carries no order: PUBLICSPOTKLINE", e.getMessage());
    }

    private static PushDataV3ApiWrapper orderPush(String symbol, int status) {
        return PushDataV3ApiWrapper.newBuilder()
                .setChannel("spot@private.orders.v3.api.pb")
                .setSymbol(symbol)
                .setSendTime(1_700_000_000_456L)
                .setPrivateOrders(order(status))
                .build();
    }

    private static PushDataV3ApiWrapper orderPush(PrivateOrdersV3Api.Builder order) {
        return orderPush("BTCUSDT", 1).toBuilder().setPrivateOrders(order).build();
    }

    private static PrivateOrdersV3Api.Builder order(int status) {
        return PrivateOrdersV3Api.newBuilder()
                .setId("order-1")
                .setClientId("client-1")
                .setTradeType(2)
                .setOrderType(1)
                .setStatus(status)
                .setQuantity("2")
                .setCumulativeQuantity("1.5")
                .setAmount("150")
                .setCumulativeAmount("112.5")
                .setAvgPrice("75")
                .setPrice("76")
                .setCreateTime(1_700_000_000_123L);
    }

    private static PushDataV3ApiWrapper klinePush(String baseVolume, String quoteAmount) {
        return PushDataV3ApiWrapper.newBuilder()
                .setChannel("spot@public.kline.v3.api.pb@BTCUSDT@Min1")
                .setSymbol("BTCUSDT")
                .setCreateTime(1_700_000_000_123L)
                .setPublicSpotKline(PublicSpotKlineV3Api.newBuilder()
                        .setInterval("Min1")
                        .setWindowStart(1_699_999_980L)
                        .setWindowEnd(1_700_000_040L)
                        .setOpeningPrice("70")
                        .setHighestPrice("80")
                        .setLowestPrice("60")
                        .setClosingPrice("75")
                        .setVolume(baseVolume)
                        .setAmount(quoteAmount))
                .build();
    }
}
