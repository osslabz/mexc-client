package net.osslabz.mexc.client;

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
import net.osslabz.mexc.client.ws.dto.SubscriptionInfo;
import net.osslabz.mexc.client.ws.dto.raw.RawOhlc;
import net.osslabz.mexc.client.ws.dto.raw.RawOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class MexcMapper {

    public static final ZoneId ZONE_ID_UTC = ZoneId.of("UTC");

    Ohlc map(CurrencyPair currencyPair, Interval interval, RawOhlc rawOhlc) {

        RawOhlc.OhlData.OhlcContent content = rawOhlc.getData().getContent();

        return Ohlc.builder()
                .asset(new OhlcAsset(new TradingAsset(Exchange.MEXC, currencyPair), interval))

                .updateTime(this.epochMillisToDate(rawOhlc.getTime()))

                .openTime(this.epochSecondsToDate(content.getOpenTime()))
                .closeTime(this.epochSecondsToDate(content.getCloseTime()))

                .openPrice(content.getOpenPrice())
                .highPrice(content.getHighPrice())
                .lowPrice(content.getLowPrice())
                .closePrice(content.getClosePrice())

                .volume(content.getVolume())
                .quantity(content.getQuantity())

                .avgPrice(this.calcAvgPrice(content))

                .build();
    }

    Order map(SubscriptionInfo subscriptionInfo, RawOrder rawOrder) {

        if (rawOrder == null || rawOrder.getData() == null) {
            throw new IllegalArgumentException("rawOrder is null or empty");
        }

        RawOrder.OrderData data = rawOrder.getData();

        return Order.builder()
                .exchangeOrderId(data.getOrderId())
                .clientOrderId(data.getClientOrderId())
                .asset(new TradingAsset(Exchange.MEXC, this.mapCurrencyPair(rawOrder.getSymbol())))
                .action(this.mapAction(data.getType()))
                .type(this.mapType(data.getTradeType()))
                .status(this.mapStatus(data.getStatus()))
                .quantity(data.getQuantity())
                .cumulativeQuantity(data.getCumulativeQuantity())
                .amount(data.getAmount())
                .cumulativeAmount(data.getCumulativeAmount())
                .avgPrice(data.getAvgPrice())
                .price(data.getPrice())
                .createdAt(epochMillisToDate(data.getCreateTime()))
                .updatedAt(epochSecondsToDate(rawOrder.getTime()))
                .build();
    }

    private OrderStatus mapStatus(Integer status) {
        //status 1:New order 2:Filled 3:Partially filled 4:Order canceled 5:Order filled partially, and then the rest of the order is canceled

        return switch (status) {
            case 1:
                yield OrderStatus.NEW;
            case 2:
                yield OrderStatus.FILLED;
            case 3:
                yield OrderStatus.PARTIALLY_FILLED;
            case 4:
                yield OrderStatus.CANCELED;
            case 5:
                yield OrderStatus.PARTIALLY_CANCELED;
            default:
                throw new UnsupportedOperationException("Unsupported status '%d': ".formatted(status));
        };
    }

    private OrderType mapType(Integer tradeType) {
        return switch (tradeType) {
            case 1:
                yield OrderType.LIMIT;
            case 5:
                yield OrderType.MARKET;
            default:
                throw new UnsupportedOperationException("Unsupported tradeType '%d': ".formatted(tradeType));
        };
    }

    private OrderAction mapAction(Integer type) {
        return switch (type) {
            case 1:
                yield OrderAction.BUY;
            case 2:
                yield OrderAction.SELL;
            default:
                throw new IllegalArgumentException("Invalid order type '%d'".formatted(type));
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


    BigDecimal calcAvgPrice(RawOhlc.OhlData.OhlcContent content) {
        return content.getVolume().compareTo(BigDecimal.ZERO) > 0 && content.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? content.getVolume().divide(content.getQuantity(), 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }


    public String mapInterval(Interval interval) {
        return switch ((int) interval.getDuration().getSeconds()) {
            case 60 -> "Min1";
            case 5 * 60 -> "Min5";
            case 15 * 60 -> "Min15";
            case 30 * 60 -> "Min30";
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
        return "spot@public.kline.v3.api@" + currencyPair.baseCurrencyCode() + currencyPair.counterCurrencyCode() + "@" + mapInterval(interval);
    }
}