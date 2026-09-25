package net.osslabz.mexc.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.osslabz.crypto.CryptoMathUtils;
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
import org.apache.commons.lang3.StringUtils;

public class MexcMapper {

    public static final ZoneId ZONE_ID_UTC = ZoneId.of("UTC");

    Ohlc map(CurrencyPair currencyPair, Interval interval, PushDataV3ApiWrapper push) {

        PublicSpotKlineV3Api kline = push.getPublicSpotKline();
        // volume is the base quantity traded, amount the quote turnover (成交量 and 成交额 in the schema).
        BigDecimal quoteVolume = decimal(kline.getAmount());
        BigDecimal baseQuantity = decimal(kline.getVolume());
        BigDecimal closePrice = decimal(kline.getClosingPrice());

        return Ohlc.builder()
                .asset(new OhlcAsset(new TradingAsset(Exchange.MEXC, currencyPair), interval))
                .updateTime(this.epochMillisToDate(eventTime(push)))
                .openTime(this.epochSecondsToDate(kline.getWindowStart()))
                .closeTime(this.epochSecondsToDate(kline.getWindowEnd()))
                .openPrice(decimal(kline.getOpeningPrice()))
                .highPrice(decimal(kline.getHighestPrice()))
                .lowPrice(decimal(kline.getLowestPrice()))
                .closePrice(closePrice)
                .volume(quoteVolume)
                .quantity(baseQuantity)
                .avgPrice(this.calcAvgPrice(quoteVolume, baseQuantity, closePrice))
                .build();
    }

    Order map(PushDataV3ApiWrapper push) {

        if (!push.hasPrivateOrders()) {
            throw new IllegalArgumentException("The push carries no order: " + push.getBodyCase());
        }

        PrivateOrdersV3Api order = push.getPrivateOrders();

        return Order.builder()
                .exchangeOrderId(order.getId())
                .clientOrderId(StringUtils.defaultIfEmpty(order.getClientId(), null))
                .asset(new TradingAsset(Exchange.MEXC, this.mapCurrencyPair(push.getSymbol())))
                .action(this.mapAction(order.getTradeType()))
                .type(this.mapType(order.getOrderType()))
                .status(this.mapStatus(order.getStatus()))
                .quantity(decimal(order.getQuantity()))
                .cumulativeQuantity(decimal(order.getCumulativeQuantity()))
                .amount(decimal(order.getAmount()))
                .cumulativeAmount(decimal(order.getCumulativeAmount()))
                .avgPrice(decimal(order.getAvgPrice()))
                .price(decimal(order.getPrice()))
                .createdAt(epochMillisToDate(order.getCreateTime()))
                .updatedAt(epochMillisToDate(eventTime(push)))
                .build();
    }

    // Kline pushes carry createTime, the orders sample in MEXC's docs sendTime; both are epoch milliseconds.
    private static long eventTime(PushDataV3ApiWrapper push) {
        return push.hasCreateTime() ? push.getCreateTime() : push.getSendTime();
    }

    // proto3 has no null: a field MEXC leaves out reads as the empty string.
    private static BigDecimal decimal(String value) {
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    private OrderStatus mapStatus(int status) {
        // status 1:New order 2:Filled 3:Partially filled 4:Order canceled 5:Order filled partially, and then the rest
        // of the order is canceled

        return switch (status) {
            case 1 -> OrderStatus.NEW;
            case 2 -> OrderStatus.FILLED;
            case 3 -> OrderStatus.PARTIALLY_FILLED;
            case 4 -> OrderStatus.CANCELED;
            case 5 -> OrderStatus.PARTIALLY_CANCELED;
            default -> throw new UnsupportedOperationException("Unsupported status '%d': ".formatted(status));
        };
    }

    private OrderType mapType(int orderType) {
        return switch (orderType) {
            case 1 -> OrderType.LIMIT;
            case 5 -> OrderType.MARKET;
            default -> throw new UnsupportedOperationException("Unsupported orderType '%d': ".formatted(orderType));
        };
    }

    private OrderAction mapAction(int tradeType) {
        return switch (tradeType) {
            case 1 -> OrderAction.BUY;
            case 2 -> OrderAction.SELL;
            default -> throw new IllegalArgumentException("Invalid tradeType '%d'".formatted(tradeType));
        };
    }

    private CurrencyPair mapCurrencyPair(String symbol) {
        if (symbol.endsWith("USDT")) {
            String baseCurrencyCode = symbol.substring(0, symbol.indexOf("USDT"));
            return new CurrencyPair(baseCurrencyCode, "USDT");
        }
        throw new IllegalArgumentException("Unsupported currency pair: " + symbol);
    }

    ZonedDateTime epochMillisToDate(long epochMillis) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE_ID_UTC);
    }

    ZonedDateTime epochSecondsToDate(long epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZONE_ID_UTC);
    }

    BigDecimal calcAvgPrice(BigDecimal volume, BigDecimal quantity, BigDecimal closePrice) {
        return CryptoMathUtils.isLargerZero(volume) && CryptoMathUtils.isLargerZero(quantity)
                ? volume.divide(quantity, 8, RoundingMode.HALF_UP)
                : closePrice;
    }

    public String mapInterval(Interval interval) {
        return switch ((int) interval.getDuration().toSeconds()) {
            case 60 -> "Min1";
            case 5 * 60 -> "Min5";
            case 15 * 60 -> "Min15";
            case 30 * 60 -> "Min30";
            case 60 * 60 -> "Min60";
            case 4 * 60 * 60 -> "Hour4";
            case 8 * 60 * 60 -> "Hour8";
            case 24 * 60 * 60 -> "Day1";
            case 7 * 24 * 60 * 60 -> "Week1";
            default -> throw new IllegalArgumentException("Unsupported interval %s".formatted(interval));
        };
    }

    public String mapSymbol(CurrencyPair currencyPair) {
        return "%s%s".formatted(currencyPair.baseCurrencyCode(), currencyPair.counterCurrencyCode());
    }

    String calcSubscriptionIdentifier(CurrencyPair currencyPair, Interval interval) {
        return "spot@public.kline.v3.api.pb@" + currencyPair.baseCurrencyCode() + currencyPair.counterCurrencyCode()
                + "@" + mapInterval(interval);
    }
}
