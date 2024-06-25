package net.osslabz.mexc.client;

import net.osslabz.crypto.CurrencyPair;
import net.osslabz.crypto.Exchange;
import net.osslabz.crypto.Interval;
import net.osslabz.crypto.Ohlc;
import net.osslabz.crypto.OhlcAsset;
import net.osslabz.crypto.TradingAsset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

class MexcMapper {

    public static final ZoneId ZONE_ID_UTC = ZoneId.of("UTC");

    Ohlc map(CurrencyPair currencyPair, Interval interval, OhlcWrapper ohlcWrapper) {

        OhlcWrapper.OhlData.OhlcContent content = ohlcWrapper.getData().getContent();

        return Ohlc.builder()
                .asset(new OhlcAsset(new TradingAsset(Exchange.MEXC, currencyPair), interval))

                .updateTime(this.epochMillisToDate(ohlcWrapper.getTime()))

                .openTime(this.epochSecondsToDate(content.getOpenTime()))
                .closeTime(this.epochSecondsToDate(content.getCloseTime()))

                .openPrice(content.getOpenPrice())
                .highPrice(content.getHighPrice())
                .lowPrice(content.getLowPrice())
                .closePrice(content.getClosePrice())

                .volume(content.getVolume())
                .avgPrice(this.calcAvgPrice(content))

                .build();
    }

    ZonedDateTime epochMillisToDate(long epochMillis) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE_ID_UTC);
    }

    ZonedDateTime epochSecondsToDate(long epochSeconds) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZONE_ID_UTC);
    }

    BigDecimal calcAvgPrice(OhlcWrapper.OhlData.OhlcContent content) {
        return content.getVolume().compareTo(BigDecimal.ZERO) > 0 && content.getQuantity().compareTo(BigDecimal.ZERO) > 0 ? content.getVolume().divide(content.getQuantity(), 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    String mapInterval(Interval interval) {
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

    String calcSubscriptionIdentifier(CurrencyPair currencyPair, Interval interval) {
        return "spot@public.kline.v3.api@" + currencyPair.baseCurrencyCode() + currencyPair.counterCurrencyCode() + "@" + mapInterval(interval);
    }
}